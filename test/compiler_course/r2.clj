(ns compiler-course.r2
  (:require [compiler-course.r1 :as r1]
            [fermor.core :as f :refer [build-graph add-edges add-vertices both-e forked]]
            [matches :refer [rule rule-list directed descend sub success on-subexpressions]]
            [matches.nanopass.pass :refer [defpass let-rulefn]]))

(def liveness*
  (rule-list
   (rule '(movq (v ?a) (v ?d))
         (let [live (-> (:live %env)
                        (disj d)
                        (conj a))]
           (-> %env
               (assoc :live live)
               (update :i concat (map vector (repeat d) (disj live a d)))
               (update :m conj [a d]))))
   (rule '(movq ?_ (v ?d))
         (-> %env
             (update :live disj d)
             (update :i concat (map vector (repeat d) (disj (:live %env) d)))))
   (rule '(movq (v ?a) ?_)
         (update %env :live conj a))
   (rule '(addq (v ?a) (v ?d))
         (update %env :live conj a))
   (rule '(addq (v ?a) ?_)
         (update %env :live conj a))))

(def liveness
  (rule '(program ?vars ??i*)
        (reduce (fn [env i]
                  (first
                   (liveness* i env
                              (fn a [r _ _]
                                [(update r :steps conj (:live r))
                                 nil])
                              (fn b []
                                [(update env :steps conj (:live env))
                                 nil]))))
                {:i [] :m [] :steps () :live #{}}
                (reverse i*))))

(defn to-graph [liveness]
  (-> (build-graph)
      (add-edges :interference (:i liveness))
      (add-edges :move (:m liveness))
      (add-vertices (map (fn [v]
                           [v {:color nil}])
                         (reduce into (:steps liveness))))
      forked))

(defn set-color [g v color]
  (f/set-document g v (assoc (f/get-document (if (f/vertex? v) v(f/get-vertex g v))) :color color)))

(defn color [v]
  (:color (f/get-document v)))

(defn saturation [v]
  (let [b (f/both [:interference] v)]
    (- (count (set (keep color b))))))

(defn movedness [v]
  (- (count (both-e :move v))))

(defn order [v]
  (+ (* 100 (saturation v))
     (movedness v)))

(comment

  (def ex
    ;; why can't I just directly def ex????
    (let [ex
          (liveness
           '(program (...)
                     (movq (int 1) (v v))
                     (movq (int 42) (v w))
                     (movq (v v) (v x))
                     (addq (int 7) (v x))
                     (movq (v x) (v y))
                     (movq (v x) (v z))
                     (addq (v w) (v z))
                     (movq (v y) (v t))
                     (negq (v t))
                     (movq (v z) (reg rax))
                     (addq (v t) (reg rax))
                     (movq (int 1) (v c))
                     (addq (v c) (v c))
                     (jmp conclusion)))]
      ex))


  (let [g (to-graph ex)
        g (set-color g 'y :red)
        g (set-color g 'x :blue)]
    (->> (f/all-vertices g)
         (sort-by order)
         (map (juxt identity saturation movedness color))))

  (liveness
   '(program
     (x.1 x.2 tmp+.3)
     (movq (int 32) (v x.1))
     (movq (int 10) (v x.2))
     (movq (v x.2) (v tmp+.3))
     (addq (v x.1) (v tmp+.3))
     (movq (v tmp+.3) (reg rax))
     (retq)))

  ,)