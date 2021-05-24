(ns compiler-course.r1
  (:require [matches :refer [rule rule-list directed descend sub success on-subexpressions]]
            [matches.nanopass.dialect :refer [=> ==> ===>]]
            [matches.nanopass.pass :refer [defpass let-rulefn]]))

(def niceid (atom 0))

(defn gennice [sym]
  (symbol (str (name sym) \. (swap! niceid inc))))

(defpass interp-exp nil
  (let-rulefn [(Expr nil
                     [(rule '(Int ?n)
                            n)
                      (rule '(Prim read)
                            (let [r (read)]
                              (when (int? r) r)))
                      (rule '(Prim - ?->e)
                            (- e))
                      (rule '(Prim + (?->e1 ?->e2))
                            (+ e1 e2))])]
    ;; FIXME: doesn't work without either <> part or a return value... %pass doesn't work
    Expr))

(interp-exp '(Prim - (Prim + ((Int 2) (Int 99)))))

(defn in [x env]
  (first (descend x env)))

;; Exercise 2:
;;
(def uniqify
  (directed (rule-list [(rule '(program ?p)
                              (do
                                (reset! niceid 0)
                                (sub (program ~(descend p)))))
                        (rule '(let ([?x ?e]) ?body)
                              (let [x' (gennice x)
                                    env (assoc-in %env [:vars x] x')]
                                (sub (let ([?x' ~(in e env)])
                                       ~(in body env)))))
                        (rule '(+ ?->a ?->b) (success))
                        (rule '(- ?->a) (success))
                        (rule '(? x symbol?) (get-in %env [:vars x]))])))

(def flatten
  (directed (rule-list (rule '(program ?->p)
                             (sub (program (~@(distinct (:v p)))
                                           ~@(:s p)
                                           (return ~(:value p)))))
                       (rule '(let ([?x ?->e]) ?->body)
                             {:v (concat (:v e) [x] (:v body))
                              :s (concat (:s e)
                                         [(sub (assign ?x ~(:value e)))]
                                         (:s body))
                              :value (:value body)})
                       (rule '(+ ?->a ?->b)
                             (let [t (gennice 'tmp+)]
                               {:v (concat (:v a) (:v b) [t])
                                :s (concat (:s a) (:s b)
                                           [(sub (assign ?t (+ ~(:value a) ~(:value b))))])
                                :value t}))
                       (rule '(- ?->a)
                             (let [t (gennice 'tmp-)]
                               {:v (concat (:v a) [t])
                                :s (concat (:s a) [(sub (assign ?t (- ~(:value a))))])
                                :value t}))
                       (rule '(read)
                             (let [t (gennice 'read)]
                               {:v [t]
                                :s [(sub (assign ?t (read)))]
                                :value t}))
                       (rule '?x {:value x}))))

(def fu (comp #'flatten #'uniqify))

(def select-instructions
  (directed (rule-list (rule '(program ?vars ??->instrs)
                             (sub (program ?vars ~@(apply concat instrs))))
                       (rule '(assign ?x (+ ?->a ?->b))
                             (let [x (sub (v ?x))]
                               (cond (= x b) (sub [(addq ?a ?b)])
                                     (= x a) (sub [(addq ?b ?a)])
                                     :else (sub [(movq ?a ?x)
                                                 (addq ?b ?x)]))))
                       (rule '(assign ?x (read))
                             (let [x (sub (v ?x))]
                               (sub [(callq read-int)
                                     (movq (reg rax) ?x)])))
                       (rule '(assign ?x (- ?->a))
                             (let [x (sub (v ?x))]
                               (if (= x a)
                                 (sub [(negq ?x)])
                                 (sub [(movq ?a ?x)
                                       (negq ?x)]))))
                       (rule '(assign ?x ?->a)
                             (let [x (sub (v ?x))]
                               (if (= x a)
                                 []
                                 (sub [(movq ?a ?x)]))))
                       (rule '(return ?->x)
                             (sub [(movq ?x (reg rax))
                                   (retq)]))
                       (rule '(? i int?)
                             (sub (int ?i)))
                       (rule '(? v symbol?)
                             (sub (v ?v))))))

(def sfu (comp #'select-instructions #'flatten #'uniqify))

(def assign-homes
  (directed (rule-list (rule '(program ?vars ??->i*)
                             (sub (program ~(* 16 (max 1 (int (Math/ceil (/ (:var-count %env 0) 2)))))
                                           ?vars ??i*)))
                       (on-subexpressions
                        (rule '(v ?x)
                              (let [offset (get-in %env [:offset x])
                                    found offset
                                    offset (or offset (* -8 (inc (:var-count %env 0))))
                                    env (if found
                                          %env
                                          (-> %env
                                              (update :var-count (fnil inc 0))
                                              (assoc-in [:offset x] offset)))]
                                (success (sub (deref rbp ?offset))
                                         env)))))))

(def asfu (comp #'assign-homes #'sfu))

(def patch-instruction
  (rule-list (rule '(?i (& ?a (deref ??_)) (& ?b (deref ??_)))
                   (sub [(movq ?a (reg rax))
                         (?i (reg rax) ?b)]))
             (rule '?x [x])))

(def patch-instructions
  (rule '(program ?size ?vars ??i*)
        (sub (program ?size ?vars
                      ~@(mapcat patch-instruction i*)))))

(def pasfu (comp #'patch-instructions #'asfu))

(def stringify
  (directed (rule-list (rule '(program ?size ?vars ??->i*)
                             (str
                              "start:\n"
                              (apply str (map #(str "\t" % "\n") i*))
                              "\tjmp conclusion\n"
                              "\t.globl main\n"
                              "main:\n"
                              "\tpushq %rbp\n"
                              "\tmovq %rsp, %rbp\n"
                              "\tsubq $" size ", %rsq\n"
                              "\tjmp start\n"
                              "conclusion:\n"
                              "\taddq $" size ", %rsp\n"
                              "\tpopq %rbp\n"
                              "\tretq\n"))
                       (rule '(int ?i)
                             (str "$" i))
                       (rule '(deref ?v ?o)
                             (str o "(%" (name v) ")"))
                       (rule '(reg ?r)
                             (str "%" r))
                       (rule '(ret) "")
                       (rule '(?x)
                             (name x))
                       (rule '(?x ?->a)
                             (str (name x) " " a))
                       (rule '(?x ?->a ?->b)
                             (str (name x) " " a ", " b)))))

(def spasfu (comp println #'stringify #'pasfu))


(comment
  [(uniqify '(program (let ([x 32]) (+ (let ([x 10]) x) x))))]

  [(uniqify '(program (let ([x 32]) (+ 10 x))))]

  ,
  (flatten (uniqify '(program (let ([x 32]) (+ (let ([x 10]) x) x)))))
  (fu
   '(program
     (let ([x (+ (- (read)) 11)])
       (+ x 41))))

  (fu '(program (let ([a 42])
                  (let ([b a])
                    b))))

  (fu '(program (let ([a 42])
                  (let ([b a])
                    b))))

  (fu '(program (let ([x 32]) (+ 10 x))))

  (sfu '(program (let ([x 32]) (+ (let ([x 10]) x) x))))

  (sfu
   '(program
     (let ([x (+ (- (read)) 11)])
       (+ x 41))))

  (sfu '(program (let ([a 42])
                   (let ([b a])
                     b))))

  [(fu '(program (let ([x 32]) (+ (- 10) x))))
   (sfu '(program (let ([x 32]) (+ (- 10) x))))]
  ,
  (asfu '(program (let ([x 32]) (+ (let ([x 10]) x) x))))

  (asfu
   '(program
     (let ([x (+ (- (read)) 11)])
       (+ x 41))))

  (asfu '(program (let ([a 42])
                    (let ([b a])
                      b))))

  [(fu '(program (let ([x 32]) (+ (- 10) x))))
   (sfu '(program (let ([x 32]) (+ (- 10) x))))
   (asfu '(program (let ([x 32]) (+ (- 10) x))))]
  ,
  (pasfu '(program (let ([x 32]) (+ (let ([x 10]) x) x))))

  (pasfu
   '(program
     (let ([x (+ (- (read)) 11)])
       (+ x 41))))

  (pasfu '(program (let ([a 42])
                    (let ([b a])
                      b))))

  [(fu '(program (let ([x 32]) (+ (- 10) x))))
   (sfu '(program (let ([x 32]) (+ (- 10) x))))
   (asfu '(program (let ([x 32]) (+ (- 10) x))))
   (pasfu '(program (let ([x 32]) (+ (- 10) x))))]
  ,
  (spasfu '(program (let ([x 32]) (+ (let ([x 10]) x) x))))

  (spasfu
   '(program
     (let ([x (+ (- (read)) 11)])
       (+ x 41))))

  (spasfu '(program (let ([a 42])
                      (let ([b a])
                        b))))

  [(fu '(program (let ([x 32]) (+ (- 10) x))))
   (sfu '(program (let ([x 32]) (+ (- 10) x))))
   (asfu '(program (let ([x 32]) (+ (- 10) x))))
   (pasfu '(program (let ([x 32]) (+ (- 10) x))))
   (spasfu '(program (let ([x 32]) (+ (- 10) x))))]
  ,)