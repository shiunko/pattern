(ns genera.core
  (:require [genera.trie :as trie]
            [genera.types :refer [IRule
                                  handler-for-args
                                  Store
                                  get-handler
                                  get-handler-with-pred
                                  with-handler
                                  get-default-handler
                                  IGenericProcedure
                                  define-handler]]
            [genera.multimethod :as gm]))

;; To make the predicate dispatch system cacheable, I would need to split up
;; generating a value from the argument and then evaluating whether that
;; resulting value matches. This would be useful for hierarchical matching like
;; what the isa? predicate in clojure does, but in many cases it would not be
;; useful. So the optimal set up is probably simple predicates or a system
;; that marked cacheable where it's broken up into a function that generates the
;; cacheable value and there's another function that evaluates whether it matches.
;; Overall, clojure has this type of dispatch nicely built already, so I don't
;; think it has a huge value here.


;; IDEA: you could be way smarter about dispatch. You could do analysis of the
;; predicates and do the dispatch in the most efficient order using the same
;; type of static pre-compilation that core.match uses. When functions are
;; added, the analysis should be redone to maximize efficiency.  Predicates
;; could also be marked as cacheable if they behave like types, enabling the
;; whole precomputation and caching mechanism, which could be autogenerated as
;; well, I think.

;; metadata

(defn- error-generic-procedure-handler [name]
  (fn [& args]
    (throw (ex-info "Inapplicable generic procedure" {:name name :args args}))))

;; Dispatcher implementations

(defrecord Rule [preds handler]
  IRule
  (handler-for-args [_ args]
    (loop [[pred & preds] preds
           [arg & args] args]
      (if pred
        (when (pred arg)
          (recur preds args))
        handler))))

(defrecord SimpleDispatchStore [rules default-handler]
  Store
  (get-handler [s args]
    (some (fn [^Rule rule]
            (handler-for-args rule args))
          rules))

  (get-handler-with-pred [s pred args]
    ;; this is not on the fast path
    (some (fn [^Rule rule]
            (when (every? (fn [[p a]] (pred p a))
                          (map vector (.preds rule) args))
              (.handler rule)))
          rules))

  (get-default-handler [s] default-handler)

  (with-handler [s applicability handler]
    ;; If the rule is already present, just change the handler. Otherwise add the rule.
    ;; Some appl. specs generate multiple applicabilities. For instance `any-arg`.
    ;; How is the rule applicability test set organized? I think they're just in the order they were added.
    (SimpleDispatchStore. (reduce (fn [rules preds]
                                    (loop [[^Rule rule & rules] rules
                                           done []]
                                      (cond (nil? rule)
                                            (conj done (->Rule preds handler))
                                            (= (.preds rule) preds)
                                            (into (conj done (->Rule preds handler)) rules)
                                            :else
                                            (recur rules (conj done rule)))))
                                  rules
                                  applicability)
                          default-handler)))


(defn- make-simple-dispatch-store [{:keys [default-handler]}]
  (->SimpleDispatchStore [] default-handler))


(defrecord TrieDispatchStore [trie default-handler]
  Store
  (get-handler [s args]
    (trie/get-a-value trie args))

  (get-handler-with-pred [s pred args]
    (trie/get-a-value-with-pred trie pred args))

  (get-default-handler [s] default-handler)

  (with-handler [s applicability handler]
    (TrieDispatchStore. (reduce (fn [trie path]
                                  ;; should just be assoc-in...?
                                  (trie/set-path-value trie path handler))
                                trie
                                applicability)
                        default-handler)))


(defn- make-trie-dispatch-store [{:keys [default-handler] :as opts}]
  (let [trie (trie/make-trie)]
    (->TrieDispatchStore trie default-handler)))


(defn- get-generic-procedure-handler [store args]
  (or (get-handler store args)
      (:default-handler store)))


(defn- generic-procedure-dispatch [store args]
  (let [^clojure.lang.IFn handler (get-generic-procedure-handler store args)]
    (.applyTo handler (seq args))))

;; Standard predicate matcher patterns

(defn match-args [& preds]
  [(mapv #(or % (constantly true))
         preds)])

(defn all-args [arity predicate]
  [(vec (repeat arity predicate))])

(defn any-arg
  ([arity predicate]
   (any-arg arity predicate (constantly true) (constantly true)))
  ([arity predicate base-predicate]
   (any-arg arity predicate base-predicate (constantly true)))
  ([arity predicate base-predicate replace?]
   (let [template (->> (if (sequential? base-predicate)
                         (cycle base-predicate)
                         (repeat arity base-predicate))
                       (take arity)
                       vec)]
     (distinct
      (if (zero? arity)
        []
        (loop [before []
               after (rest template)
               result []]
          (if (seq after)
            (let [to-replace (nth template (count before))]
              (recur (conj before to-replace)
                     (rest after)
                     (conj result (into (conj before (if (replace? to-replace (count before))
                                                       predicate
                                                       to-replace))
                                        after))))
            (conj result (conj before predicate)))))))))

;; Generic procedures

(deftype GenericProcedure [name arity store]
  clojure.lang.IFn
  (applyTo [_ args]
    (generic-procedure-dispatch @store args))
  (invoke [_]
    (generic-procedure-dispatch @store []))
  (invoke [_ a0]
    (generic-procedure-dispatch @store [a0]))
  (invoke [_ a0 a1]
    (generic-procedure-dispatch @store [a0 a1]))
  (invoke [_ a0 a1 a2]
    (generic-procedure-dispatch @store [a0 a1 a2]))
  (invoke [_ a0 a1 a2 a3]
    (generic-procedure-dispatch @store [a0 a1 a2 a3]))
  (invoke [_ a0 a1 a2 a3 a4]
    (generic-procedure-dispatch @store [a0 a1 a2 a3 a4]))
  (invoke [_ a0 a1 a2 a3 a4 a5]
    (generic-procedure-dispatch @store [a0 a1 a2 a3 a4 a5]))
  (invoke [_ a0 a1 a2 a3 a4 a5 a6]
    (generic-procedure-dispatch @store [a0 a1 a2 a3 a4 a5 a6]))
  (invoke [_ a0 a1 a2 a3 a4 a5 a6 a7]
    (generic-procedure-dispatch @store [a0 a1 a2 a3 a4 a5 a6 a7]))
  (invoke [_ a0 a1 a2 a3 a4 a5 a6 a7 a8]
    (generic-procedure-dispatch @store [a0 a1 a2 a3 a4 a5 a6 a7 a8]))

  clojure.lang.Named
  (getName [_] (str name))

  IGenericProcedure
  (define-handler [p applicability handler]
    (swap! store with-handler applicability handler)))


(defn generic-procedure-constructor [name arity default-handler]
  ;; this is called by defgenera
  (assert (pos-int? arity))
  (let [dispatch-store-maker (if (= 1 arity)
                               make-simple-dispatch-store
                               make-trie-dispatch-store)]
    (let [default-handler (or default-handler
                              (error-generic-procedure-handler name))
          store (dispatch-store-maker {:default-handler default-handler})]
      (->GenericProcedure name arity (atom store)))))


(defn assign-handler! [procedure handler & preds]
  (define-handler procedure (apply match-args preds) handler))


(defn find-handler
  "This is similar to [[specialize]] but uses the actual applicability
  predicates to find the function rather than using an example."
  {:see-also ["specialize"]}
  [generic-procedure applicability]
  (or (get-handler-with-pred @(.store generic-procedure) = applicability)
      (get-default-handler @(.store generic-procedure))))


(defn specialize
  "Allow pre-selecting a defgen or defmethod specialization for inside an inner loop, etc."
  {:see-also ["find-handler"]}
  [procedure & args]
  (condp instance? procedure
        clojure.lang.MultiFn (apply gm/dispatch-fn procedure args)
        GenericProcedure (get-generic-procedure-handler @(.store procedure) args)))