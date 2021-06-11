(ns compiler-course.r1
  (:require [compiler-course.r1-allocator :refer [liveness to-graph allocate-registers*
                                                  caller-saved-registers callee-saved-registers
                                                  var-locations with-allocated-registers]]
            [compiler-course.dialects :refer [r1-keyword?]]
            [matches.nanopass.dialect :refer [all-dialects =>:to show-parse]]
            [matches :refer [descend directed on-subexpressions rule rule-list rule-list!
                             sub success subm rule-simplifier matcher
                             => dialects validate ok ok?]]
            [matches.types :refer [child-rules]]
            [clojure.string :as str]))

(def niceid (atom 0))

(defn gennice [sym]
  (symbol (str (name sym) \. (swap! niceid inc))))

(defn in [x env]
  (first (descend x env)))

;; Give every var a unique name

(def uniqify*
  (dialects
   (=> R1 R1Fun)
   (directed (rule-list [(rule '(define (?v [?v* ?type*] ...) ?type ?e)
                               (let [[v'* env] (reduce (fn [[v'* env] x]
                                                         (let [x' (gennice x)]
                                                           [(conj v'* x') (assoc-in env [:vars x] x')]))
                                                       [[] %env]
                                                       v*)
                                     e (in e env)]
                                 (sub (define (?v [?v'* ?type*] ...) ?type ?e))))
                         (rule '((?:= let) ([?v:x ?e]) ?e:body)
                               (let [x' (gennice x)
                                     env (assoc-in %env [:vars x] x')]
                                 (sub (let ([?x' ~(in e env)])
                                        ~(in body env)))))
                         (rule '(if ?->e ?->e:then ?->e:else))
                         (rule '(?->e:f ??->e:args))
                         (rule '?v (get-in %env [:vars v]))]))))

(def uniqify-and-shrink-rfun*
  (rule '[(define (?v ??argdef*) ??more) ... ?e]
        (let [[defs env] (reduce (fn [[defs env] [v argdef* more]]
                                   (let [v' (gennice v)]
                                     [(conj defs (sub (define (?v' ??argdef*) ??more)))
                                      (assoc-in env [:vars v] v')]))
                                 [[] {}] (map vector v argdef* more))
              defs (mapv #(first (uniqify* % env)) defs)
              main (first (uniqify* (sub (define (main) Integer ?e)) env))
              defs (conj defs main)]
          (sub (program ??defs)))))

(defn uniqify
  {:=>/from 'R1 :=>/to 'R1Fun}
  [p]
  (reset! niceid 0)
  (uniqify-and-shrink-rfun* p))

(comment
  (uniqify '[(define (a [x Integer]) Integer 123) (a 1)]))

;; Shrink the number of instructions we need to support (by expanding to equivalent expressions)

(def immutable-expr?*
  "If the expression is immutable, the result will be unmodified.
   If the expression is mutable, the result will be modified."
  (on-subexpressions
   ;; NOTE: all expressions that either mutate or read mutable data must be captured here:
   ;; NOTE: this isn't perfect because it doesn't distinguish (future) quoted expressions, but that could be added.
   (rule-list (rule '(read) (success nil))
              (rule '(vector-ref ??_) (success nil))
              (rule '(vector-set! ??_) (success nil)))))

(defn immutable-expr? [exp]
  (= exp (immutable-expr?* exp)))

(def shrink
  (dialects
   (=> R1Fun Shrunk)
   (let [preserve-order (fn [n a b expr]
                          (let [t (gennice n)]
                            (sub (let ([?t ?a])
                                   ~(expr t)))))]
     (directed
      (rule-list
       (rule '(program ??->define))
       (rule '(define ?form ?type ?->e:body))
       (rule '(eq? ?->same ?->same)
             (if (immutable-expr? same)
               true
               (sub (eq? ?same ?same))))
       (rule '(- ?->e:a ?->e:b) (sub (+ ?a (- ?b))))
       (rule '(or ?->e:a ?->e:b) (sub (if ?a true ?b)))
       (rule '(and ?->e:a ?->e:b) (sub (if ?a (if ?b true false) false)))
       (rule '(if ?->e:exp ?->same ?->same)
             (if (and (immutable-expr? exp)
                      (immutable-expr? same))
               (success same)
               (sub (if ?exp ?same ?same))))
       ;; < is our canonical choice, so alter <= > >=
       (rule '(<= ?->e:a ?->e:b)
             (preserve-order 'le a b #(sub (not (< ?b ~%)))))
       (rule '(> ?->e:a ?->e:b)
             (preserve-order 'gt a b #(sub (< ?b ~%))))
       (rule '(>= ?->e:a ?->e:b)
             (sub (not (< ?a ?b))))
       (rule '(| (read)
                 (- ?->e) (+ ?->e0 ?->e1)
                 (not ?->e)
                 (?cmp ?->e0 ?->e1)
                 ((?:= let) ([?v ?->e]) ?->e:body)
                 (vector ??->e*) (vector-length ?->e)
                 (vector-ref ?->e ?i) (vector-set! ?->e0 ?i ?->e1)
                 (void)))
       (rule '(?->e:f ??->e:args)
             ;;(when-not (r1-keyword? f)
             (sub (call (funref ?f) ??args))))))))

;; Add type metadata to everything possible

(defn tag [n]
  (cond (int? n) 'Integer
        (boolean? n) 'Boolean))

(def add-types
  (dialects
   (=> Shrunk Typed)
   (letfn [(get-type [e]
             (or (meta e) {::type (tag e)}))]
     (directed
      ;; TODO: need a way to cleanly specify that I want the result meta merged with the input meta. Basically express:
      ;;
      ;; (success (with-meta ... (merge (meta (:rule/datum %env)) {... ...})))
      (rule-list (rule '((?:= let) ([?v ?->e]) ?e:b)
                       (let [env (assoc-in %env [::type v] (get-type e))
                             v (in v env) ;; to simplify checking
                             b (in b env)]
                         (success (subm (let ([?v ?e]) ?b)
                                        (get-type b))
                                  env)))
                 (rule '((?:as op (| + - if)) ??->e:x* ?->e:x)
                       (success
                        (subm (?op ??x* ?x) (get-type x))))
                 (rule '((?:as op (| < eq? not)) ??->e:x* ?->e:x)
                       (success
                        (subm (?op ??x* ?x) {::type (tag true)})))
                 (rule '(read) (success (subm (read) {::type (tag 1)})))
                 (rule '(void) (success (subm (void) {::type 'Void})))
                 (rule '(global-value ?v:l)
                       (success (subm (global-value ?l) {::type 'Integer})))
                 (rule '(collect ?i)
                       (success (subm (collect ?i) {::type 'Void})))
                 (rule '(vector ??->e*)
                       (success (subm (vector ??e*)
                                      {::type (sub (Vector ~@(map (comp ::type get-type) e*)))})))
                 (rule '(vector-ref ?->e:v ?->i)
                       (let [t (::type (meta v))]
                         (if (and (sequential? t) (nth t (inc i) nil))
                           (success (subm (vector-ref ?v ?i)
                                          {::type (nth t (inc i))}))
                           (sub (invalid-access (vector-ref ?v ?i) ::type ?t)))))
                 (rule '(vector-set! ??->e)
                       (success (subm (vector-set! ??e) {::type 'Void})))
                 (rule '(allocate ?e:v ?type:t)
                       (success (subm (allocate ?v ?t) {::type t})))
                 (rule '?v
                       (success (with-meta v (get-in %env [::type v])))))))))

;; Expand the inner workings of (vector ...)

(defmacro m! []
  `(meta (:rule/datum ~'%env)))

(def expose-allocation
  (dialects
   (=> Typed Alloc)
   (rule-simplifier
    (rule '(vector ??e*)
          (let [t (::type (m!))]
            (sub (vector> ~t [] [??e*] [~@(rest t)]))))
    (rule '(vector> ?type ?names [?e ??e*] [?t ??t*])
          (let [n (gennice 'vec)
                ;; building names in reverse
                names (conj names n)]
            (sub (let ([~(with-meta n {::type t}) ?e])
                   (vector> ?type ?names [??e*] [??t*])))))
    (rule '(vector> ?type ?names [] [])
          (let [len (count names)
                ;; types will be inferred by add-types below.
                v (gennice 'vector)
                _ (gennice '_)
                bytes (* 8 (inc len))]
            (add-types
             (sub
              (let ([?_ (if (< (+ (global-value free_ptr) ?bytes)
                               (global-value fromspace_end))
                          (void)
                          (collect ?bytes))])
                (let ([?v (allocate ?len ?type)])
                  (vector< ?v ?names)))))))
    (rule '(vector< ?v [??n* ?n])
          ;; using names in reverse, so n* count is the vector position
          (let [idx (count n*)
                _ (with-meta (gennice '_) {::type 'Void})]
            (add-types
             (sub (let ([?_ (vector-set! ?v ?idx ?n)])
                    (vector< ?v [??n*]))))))
    (rule '(vector< ?v [])
          v))))

;; Remove complex operators / operands

(declare rco-exp)

(def rco-atom
  (dialects
   (=> Alloc Simplified)
   (let [wrap (fn wrap [name args exp]
                (let [t (gennice name)
                      w (reduce (fn [w arg]
                                  (comp w (:wrap arg identity)))
                                identity args)]
                  {:wrap (fn [r]
                           (w (sub (let ([?t ?exp])
                                     ?r))))
                   :value t}))]
     (directed
      (rule-list (rule '((?:= let) ([?v ?e]) ?e:body)
                       (wrap 'let nil
                             (subm (let ([?v ~(rco-exp e)])
                                     ~(rco-exp body))
                                   (m!))))
                 (rule '((? op #{+ < eq? - not}) ??->e:args)
                       (wrap (symbol (str (name op) ".tmp")) args
                             (subm (?op ~@(map :value args)) (m!))))
                 (rule '(read)
                       (wrap 'read nil
                             (with-meta '(read) {::type 'Integer})))
                 (rule '(global-value ?name)
                       (wrap name nil (:rule/datum %env)))
                 (rule '(vector-ref ?->e:v ?i)
                       (wrap (symbol (str "vec+" i)) [v]
                             (subm (vector-ref ~(:value v) ?i) (m!))))
                 (rule '(vector-set! ?->e:v ?i ?->e)
                       (wrap (symbol (str "vec+" i)) [v e]
                             (subm (vector-set! ~(:value v) ?i ~(:value e)) (m!))))
                 (rule '(not ?->e)
                       (wrap 'not [e]
                             (subm (not ~(:value e)) (m!))))
                 (rule '?i
                       {:value i})
                 (rule '?other
                       {:value other}))))))

(defmacro rco-atoms [vars exp]
  `(let [r# (reduce (fn [r# exp#]
                      (let [x# (rco-atom exp#)
                            wrap# (:wrap x#)
                            r# (update r# :values conj (:value x#))]
                        (if wrap#
                          ;; compose in reverse
                          (assoc r# :wrap (comp (:wrap r#) wrap#))
                          r#)))
                    {:wrap identity :values []} ~vars)
         wrap# (:wrap r#)
         ~vars (:values r#)]
     (wrap# ~exp)))

(def rco-exp
  (dialects
   (=> Alloc Simplified)
   (let [preserve-not (comp first
                            (directed (rule-list (rule '(not ?->e:x))
                                                 (rule '?_ (:exp %env)))))]
     (directed
      (rule-list (rule '((?:= let) ([?v:a ?->e:b]) ?->e:body))
                 (rule '((? op #{vector-set! + < eq? vector-ref - not global-value})
                         ??e:args)
                       (rco-atoms args (subm (?op ??args) (m!))))
                 (rule '(?:letrec [maybe-not (?:as nots
                                                   (?:fresh [nots]
                                                            (| (not $maybe-not)
                                                               ?->e:exp)))]
                                  (if $maybe-not ?->e:then ?->e:else))
                       ;; preserve the not in (if (not ...) ...) because a future
                       ;; pass can eliminate the not by swapping the then/else
                       ;; order. It could also be done here but I'd need to do
                       ;; more work here and it's already done there...
                       ;; FIXME? what about (if (let ...) ...)?
                       (let [exp (preserve-not nots {:exp exp})]
                         (subm (if ?exp ?then ?else) (m!)))))))))

(defn remove-complex-opera*
  "Remove complex operators/operands by let binding them around any complex expression."
  {:=>/from 'Alloc  :=>/to 'Simplified}
  [p]
  (rco-exp p))

;; Explicate expressions: remove nesting (aka flatten)

(declare x-pred x-assign)

(let [x-assign*
      (dialects
       (=> Simplified Explicit)
       (rule-list
        (rule '(if ?e ?then ?else)
              (let [then (x-assign (:var %env) then (:cont %env))
                    else (x-assign (:var %env) else (:cont %env))]
                (x-pred e then else)))
        (rule '((?:= let) ([?v ?e]) ?body)
              (let [body (x-assign (:var %env) body (:cont %env))]
                (x-assign v e body)))
        (rule '?value (-> (:cont %env)
                          (update :s (fn [s] (into [(sub (assign ~(:var %env) ?value))] s)))
                          (update :v (fnil conj #{}) (:var %env))))))]
  (defn x-assign [v exp cont]
    (first (x-assign* exp {:var v :cont cont}))))

(defn pred-block [{:keys [then else]} f]
  (let [then (assoc then :label (gennice 'then))
        else (assoc else :label (gennice 'else))]
    {:v (into (:v then) (:v else))
     :b (merge {(:label then) (dissoc then :b)
                (:label else) (dissoc else :b)}
               (:b then)
               (:b else))
     :s (f (:label then) (:label else))}))

(let [x-pred*
      (dialects
       (=> Simplified Explicit)
       (rule-list
        (rule '(if ?e ?then ?else)
              (let [then (x-pred then (:then %env) (:else %env))
                    else (x-pred else (:then %env) (:else %env))]
                (x-pred e then else)))
        (rule '(not ?e)
              (x-pred e (:else %env) (:then %env)))
        (rule true (:then %env))
        (rule false (:else %env))
        (rule '((?:= let) ([?v ?e]) ?body)
              (let [body (x-pred body (:then %env) (:else %env))]
                (x-assign v e body)))
        (rule '(??items)
              (pred-block %env #(sub [(if (??items) (goto ~%1) (goto ~%2))])))
        (rule '?other
              (x-pred (sub (eq? ?other true)) (:then %env) (:else %env)))))]
  (defn x-pred [exp then else]
    (first (x-pred* exp {:then then :else else}))))

(def x-tail
  (dialects
   (=> Simplified Explicit)
   (rule-list
    (rule '(if ?e ?then ?else)
          (let [then (x-tail then)
                else (x-tail else)]
            (x-pred e then else)))
    (rule '((?:= let) ([?v ?e]) ?body)
          (let [body (x-tail body)]
            (x-assign v e body)))
    (rule '?other {:s [(sub (return ?other))]}))))

(defn explicate-control
  {:=>/from 'Simplified :=>/to 'Explicit}
  [p]
  (dialects
   (=> Simplified Explicit)
   (let [p (x-tail p)
         blocks (reduce-kv (fn [blocks l b]
                             (assoc blocks l
                                    (sub (block ?l [~@(sort (:v b))]
                                                ~@(:s b)))))
                           {} (assoc (:b p) 'start (assoc p :label 'start)))]
     (sub (program [~@(:v p)] ~blocks)))))

;; Uncover locals

(def uncover-locals
  (dialects
   (=> Explicit Uncovered)
   (directed
    (rule-list
     (rule '(program ?vars (& ?blocks (?:*map ?lbl ?->block*)))
           (sub (program ~(apply merge-with #(or %1 %2) (filter map? block*))
                         ?blocks)))
     (rule '(block ?lbl ?vars ??->stmt* ?tail)
           (apply merge-with #(or %1 %2) (filter map? stmt*)))
     (rule '(assign ?v ?e)
           (let [vt (::type (meta v))
                 et (::type (meta e))]
             (if (and vt et (not= vt et))
               {v {v vt e et}}
               {v (or vt et)})))))))

;; Select instructions: rewrite as data representing X86 assembly

(def unv-rax
  "This is an annoying hack because I don't mark symbols as (v x) in an earlier
  pass, so I end up with (v (reg rax)) in my return clause. This cleans that
  up."
  (on-subexpressions (rule '(v (reg rax)) '(reg rax))))

(def select-inst-cond*
  (dialects
   (=> Uncovered Selected)
   (rule-list!
    (rule '((? op #{< eq?}) ?e:a ?e:b)
          (let [v (:v %env)]
            (sub [(cmpq ?b ?a)
                  (set ?op (byte-reg al))
                  (movzbq (byte-reg al) ?v)])))
    (rule '(not ?->e:a)
          (let [v (:v %env)]
            (if (= v a)
              (sub [(xorq (int 1) ?v)])
              (sub [(movq ?a ?v)
                    (xorgq (int 1) ?v)]))))
    (rule '(vector-ref ?vec ?i)
          (let [v (:v %env)]
            (sub [(movq (v ?vec) (reg r11))
                  (movq (deref 8 ~(inc i) (reg r11)) ?v)])))
    (rule '(v ?x)
          (sub [(movq (v ?x) ~(:v %env))])))))

(defn select-inst-cond
  {:=>/from 'Uncovered :=>/to 'Selected}
  [x env]
  (first (select-inst-cond* x env)))

(defn make-tag [len type*]
  (bit-or 1
          (bit-shift-left len 1)
          (bit-shift-left
           (reduce bit-or
                   (map-indexed (fn [i t]
                                  (condp = t
                                    'Vector (bit-shift-left 1 i)
                                    0))
                                type*))
           7)))

(def select-instructions*
  ;; TODO: split to assign and tail versions.. See hints here
  ;; https://iucompilercourse.github.io/IU-P423-P523-E313-E513-Fall-2020/lecture-Oct-6.html
  ;;
  ;; NOTE: The (return ...) rule can cause (assign (reg rax) ...) to be run
  ;; which violates the Uncovered language, so assign can't validate (assign ?v
  ;; ...)  return (below) or with x as a symbol.
  (dialects
   (=> Uncovered Selected)
   (directed (rule-list (rule '(program ?vars ?blocks)
                              (let [blocks (reduce-kv (fn [blocks l b]
                                                        (assoc blocks l (descend b)))
                                                      {} blocks)]
                                (sub (program ?vars ?blocks))))
                        (rule '(block ?label ?vars ??->instrs)
                              (sub (block ?label ?vars ~@(apply concat instrs))))
                        (rule '(assign ?->any:x (+ ?->atm:a ?->atm:b))
                              (cond (= x b) (sub [(addq ?a ?b)])
                                    (= x a) (sub [(addq ?b ?a)])
                                    :else (sub [(movq ?a ?x)
                                                (addq ?b ?x)])))
                        (rule '(assign ?->any:x (read))
                              (sub [(callq read-int)
                                    (movq (reg rax) ?x)]))
                        (rule '(assign ?->any:x (vector-ref ?->v:vec ?i))
                              (sub [(movq ?vec (reg r11))
                                    (movq (deref 8 ~(inc i) (reg r11)) ?x)]))

                        (rule '(assign ?->any:x (vector-set! ?->v:vec ?i ?->atm:val))
                              (sub [(movq ?vec (reg r11))
                                    (movq ?val (deref 8 ~(inc i) (reg r11)))
                                    (movq (int 0) ?x)]))

                        (rule '(assign ?->any:x (allocate ?i:len (Vector ??type*)))
                              (let [tag (make-tag len type*)
                                    free (sub (v ~(gennice 'free)))]
                                (sub [(movq (global-value free_ptr) ?free)
                                      (addq (int ~(* 8 (inc len))) (global-value free_ptr))
                                      (movq ?free (reg rax))
                                      (movq (int ?tag) (deref 0 (reg rax))) ;; why use deref here??
                                      (movq ?free ?x)])))
                        (rule '(assign ?->any:x (collect ?->i:bytes))
                              ;; TODO: can I deal with the existence of these
                              ;; registers in the allocator and not cause
                              ;; clobbering without just removing them from the list of avail regs?
                              ;; OOOH yes, these method calls need to interfere with the callee-save registers.
                              (sub [(movq (reg r15) (reg rdi))
                                    (movq ?bytes (reg rsi))
                                    (callq collect)]))

                        (rule '(assign ?->any:x (- ?->e:a))
                              (if (= x a)
                                (sub [(negq ?x)])
                                (sub [(movq ?a ?x)
                                      (negq ?x)])))
                        (rule '(assign ?->any:x ((? op #{< eq?}) ?->atm:a ?->atm:b))
                              (select-inst-cond (sub (?op ?a ?b)) {:v x}))
                        (rule '(assign ?->any:x (not ?->pred:a))
                              (select-inst-cond (sub (not ?a)) {:v x}))
                        (rule '(assign ?->any:x ?->e:a)
                              (if (= x a)
                                []
                                (sub [(movq ?a ?x)])))
                        (rule '(return ?->e:x)
                              (concat (unv-rax
                                       ;; Will produce a vector of statements:
                                       (descend (sub (assign (reg rax) ?x))))
                                      ['(retq)]))

                        (rule '(if ((? cmp #{< eq?}) ?->atm:a ?->atm:b) (goto ?lbl:then) (goto ?lbl:else))
                              (sub [(cmpq ?b ?a)
                                    (jump ?cmp ?then)
                                    (jump true ?else)]))

                        (rule '(if ?->pred:exp (goto ?lbl:then) (goto ?lbl:else))
                              (concat
                               (select-inst-cond exp {:v (sub (v ~(gennice 'if)))})
                               (sub [(cmpq (int 1) (v tmp))
                                     (jump eq? ?then)
                                     (jump true ?else)])))

                        (rule true '(int 1))
                        (rule false '(int 0))
                        (rule '?i
                              (sub (int ?i)))
                        (rule '?v
                              (sub (v ?v)))
                        (rule '(void) '(int 0))))))

(defn select-instructions
  {:=>/from 'Uncovered :=>/to 'Selected}
  [x]
  (select-instructions* x))

;; Allocate registers (see r1-allocate ns)

(defn allocate-registers
  {:=>/from 'Selected :=>/to 'RegAllocated}
  [prog]
  (let [g (to-graph (liveness prog))
        g (allocate-registers* g)
        [_ var-types blocks] prog
        var-locs (var-locations var-types g)
        blocks (-> (vec (vals blocks))
                   (with-allocated-registers {:loc var-locs}))]
    (sub (program ?var-types ?var-locs ?blocks))))

;; Remove unallocated vars (if a var is set but never used)
;; This is not part of the instructor's compiler but seems good/simple. It falls
;; out because unused vars never get allocated to registers by my allocator so
;; they stay as (v ...). This could easily be part of patch-instructions.

(def remove-unallocated
  (dialects
   (=> RegAllocated RemoveUnallocated)
   (on-subexpressions
    (rule-list (rule '(movq ?arg0 (v ?v)) (success nil))
               (rule '(block ?lbl ?vars ??ins*)
                     (sub (block ?lbl ?vars ~@(remove nil? ins*))))))))

;; Combine blocks when a jump is not needed

(def remove-jumps
  (dialects
   (=> RemoveUnallocated RemoveUnallocated)
   (directed (rule-list (rule '(program ?vars ?var-locs [(& ?blocks ?->jumps) ...])
                              (let [blocks (reduce (fn [m [_ label :as b]]
                                                     (assoc m label b))
                                                   {} blocks)
                                    linear-jumps
                                    (->> (group-by second jumps)
                                         (filter #(= 1 (count (val %))))
                                         vals
                                         (apply concat)
                                         (into {}))
                                    blocks
                                    (reduce-kv (fn [blocks from to]
                                                 (let [from (loop [b (blocks from)]
                                                              (if (symbol? b)
                                                                (recur (blocks b))
                                                                (:label b)))]
                                                   (if (and (blocks from) (blocks to))
                                                     (-> blocks
                                                         (update from (fn [b]
                                                                        (sub (~@(butlast b)
                                                                              ~@(drop 3 (blocks to))))))
                                                         (assoc to from))
                                                     blocks)))
                                               blocks linear-jumps)]
                                (sub (program ?vars ?var-locs [~@(remove symbol? (vals blocks))]))))
                        (rule '(block ?from ?vars ??stmt* (jump ?jc ?to))
                              [from to])
                        (rule '(block ??any)
                              (success nil))))))

;; Remove copy to self, +/- 0 instructions

(def patch-instructions
  (dialects
   (=> RemoveUnallocated Patched)
   (directed (rule-list (rule '(program ?vars ?var-locs [??->block*]))
                        (rule '(block ?lbl ?vars ??->stmt* ?tail)
                              (sub (block ?lbl ?vars ~@(apply concat stmt*) ?tail)))
                        (rule '(addq (int 0) ?arg) [])
                        (rule '(subq (int 0) ?arg) [])
                        (rule '(movq ?arg ?arg) [])
                        (rule '?x [x])))))

;; Capture callee-save registers on entry and restore them on exit

(defn save-registers* [var-locs]
  (->> (vals var-locs)
       (map second)
       distinct
       (filter callee-saved-registers)
       (map-indexed (fn [i reg]
                      (sub (movq (reg ?reg) (stack* ?i)))))
       vec))

(def save-registers
  (dialects
   (=> Patched Patched+)
   (rule '(program ?vars ?var-locs ?block*)
         (let [save-regs (save-registers* var-locs)]
           (sub (program ?vars ?var-locs ?save-regs ?block*))))))

;; Stringify: Turn the data representing X86 assembly into actual assembly

(defn stack-var-max [var-locs]
  (->> (vals var-locs)
       (filter #(= 'stack (first %)))
       (map second)
       (apply max 0)))

(defn stack-size [offset var-locs]
  (let [stack-vars (+ offset (stack-var-max var-locs))]
    (* 16 (max 1 (int (Math/ceil (/ stack-vars 2)))))))

(def stringify*
  (dialects
   (=> Patched+ nil)
   (letfn [(fi [i*] (map (fn [x]
                           (let [x (if (sequential? x) x [x])]
                             (sub ["    " ??x])))
                         i*))
           (init-gc [root-stack-size heap-size root-spills]
             (sub [(movq (int ?root-stack-size) (reg rdi))
                   ;; TODO: how to distinguish "root stack" size and heap size?
                   ;; heap must include all of the memory pointed to (or default start size?)
                   ;; root-stack may also be a fixed size?
                   ;; root-spills is the max number currently used??
                   (movq (int ?heap-size) (reg rsi))
                   (callq initialize)
                   (movq (global-value rootstack_begin) (reg r15))
                   ~@(map (fn [i]
                            (sub (movq (int 0) (deref ?i (reg r15)))))
                          (range root-stack-size))
                   (addq (int ?root-spills) (reg r15))]))]

     (directed (rule-list (rule '(program ?vars ?var-locs [??->save-regs] ?block*)
                                (let [offset (count save-regs)
                                      block* (apply concat (map #(first (descend % {:stack-offset offset}))
                                                                block*))
                                      size (stack-size offset var-locs)
                                      heap-size (->> (vals var-locs)
                                                     (keep (matcher '(heap ?h)))
                                                     (map first)
                                                     (apply max -1)
                                                     inc)]
                                  (sub
                                   [[".globl main"]
                                    ~@block*
                                    ["main:"]
                                    ["    pushq %rbp"]
                                    ["    movq %rsp, %rbp"]
                                    ~@(fi save-regs)
                                    [~(str "    subq $" size ", %rsp")]
                                    ~@(fi (map descend (init-gc heap-size
                                                                heap-size
                                                                heap-size)))
                                    ;; TODO: inline start. This could be treated
                                    ;; as a nearly empty regular block, existing
                                    ;; from the beginning, then with instructions
                                    ;; added to it at the end?
                                    ["    jmp start"]
                                    ["conclusion:"]
                                    [~(str "    addq $" size ", %rsp")]
                                    ["    popq %rbp"]
                                    ["    retq"]])))

                          (rule '(block ?lbl ?vars ??->all*)
                                (list* [(str (name lbl) ":")]
                                       (fi all*)))

                          (rule '(byte-reg ?r)                  (str "%" r))
                          (rule '(deref ?i:offset (reg ?v))           (str offset "(%" (name v) ")"))
                          (rule '(deref ?i:scale ?i:offset (reg ?v))  (str scale "(" offset ")(%" (name v) ")"))
                          (rule '(global-value ?lbl)            (str (name lbl) "(%rip)"))
                          (rule '(int ?i)                       (str "$" i))
                          (rule '(reg ?r)                       (str "%" r))
                          (rule '(stack* ?i)                    (str (* -8 (inc i)) "(%rbp)"))
                          (rule '(stack ?i)
                                (str (* -8 (inc (+ (:stack-offset %env) i)))
                                     "(%rbp)"))
                          (rule '(heap ?i)                      (str (* 8 (inc i)) "(%r15)"))

                          (rule '(set ?jc ?->bytereg)
                                (let [flag ('{< l eq? e} jc)]
                                  (list "set" (str flag) " " bytereg)))
                          (rule '(callq ?lbl)             (list "callq " (name lbl)))
                          (rule '(jump < ?lbl)            (list "jl " (name lbl)))
                          (rule '(jump eq? ?lbl)          (list "je " (name lbl)))
                          (rule '(jump true ?lbl)         (list "jmp " (name lbl)))
                          (rule '(retq)                   ["jmp conclusion"])
                          (rule '(?x ?->a)                (list (name x) " " a))
                          (rule '(?x ?->a ?->b)           (list (name x) " " a ", " b)))))))

(defn stringify
  {:=>/from 'Patched+}
  [p]
  (stringify* p))

;; Turn the list of strings into one big string

(defn combine-strings [p]
  (apply str (map #(apply str (conj % "\n")) p)))

(def ->shrink (comp #'shrink #'uniqify))
(def ->type (comp #'add-types #'->shrink))
(def ->expose-alloc (comp #'expose-allocation #'->type))
(def ->simple (comp #'remove-complex-opera* #'->expose-alloc))
(def ->flatten (comp #'explicate-control #'->simple))
(def ->uncover (comp #'uncover-locals #'->flatten))
;; ...
(def ->select (comp #'select-instructions #'->uncover))
(def ->live (comp #'liveness #'->select))
(def ->alloc (comp #'allocate-registers #'->select))
(def ->tidied (comp #'remove-unallocated #'->alloc))
(def ->jump (comp #'remove-jumps #'->tidied))
(def ->patch (comp #'patch-instructions #'->jump))
(def ->reg (comp #'save-registers #'->patch))
(def ->str (comp #'stringify #'->reg))

(def passes
  [#'uniqify
   #'shrink
   #'add-types
   #'expose-allocation
   #'remove-complex-opera*
   #'explicate-control
   #'uncover-locals
   #'select-instructions
   #'allocate-registers
   #'remove-unallocated
   #'remove-jumps
   #'patch-instructions
   #'save-registers])

(defn valid-asm? [p]
  (every? #(every? string? %) p))

;; TODO: adopt this or something similar into nanopass.
(defn tester [start-dialect passes finalize valid-output?]
  (fn [form]
    (loop [form form [pass & more] (cons identity passes) results []]
      (let [dialect (if (= identity pass)
                      start-dialect
                      (or (=>:to (meta pass) nil)
                          (=>:to (meta @pass) nil)))
            dialect (if (symbol? dialect)
                      (@all-dialects dialect)
                      dialect)
            result (try (pass form)
                        (catch Exception e {:error e}))
            results (conj results [pass (:name dialect) result])
            v (when dialect
                (when-not (:error result) (validate dialect result)))]
        (if (or (and (ok? v)) (not dialect))
          (if more
            (recur result more results)
            (let [s (finalize result)]
              (if (valid-output? s)
                v
                s)))
          (conj results v))))))

(def test-pipeline (tester 'R1 passes stringify valid-asm?))
