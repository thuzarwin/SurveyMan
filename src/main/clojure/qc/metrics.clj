(ns qc.metrics
    (:gen-class
        :name qc.Metrics
        :implements [qc.IQCMetrics]
        )
    (:import (interstitial IQuestionResponse ISurveyResponse OptTuple Record)
             (survey Block$BranchParadigm)
             (java.util Collections List))
    (:import (qc IQCMetrics Interpreter PathMetric RandomRespondent RandomRespondent$AdversaryType)
             (survey Question Component Block Block$BranchParadigm Survey))
    (:require [clojure.math.numeric-tower :as math]
              [incanter.stats])
    )

(def alpha (atom 0.05))
(def bootstrap-reps (atom 1000))
(def cutoffs (atom {}))
;; these should be read in from a config file and be the same as those in Library
(def FEDMINWAGE 7.25)
(def timePerQuestionInSeconds 10)

(defn log2
  [x]
  (/ (Math/log x) (Math/log 2.0))
  )

(defn getRandomSurveyResponses
  [survey n]
  (clojure.core/repeatedly n #(RandomRespondent. survey RandomRespondent$AdversaryType/UNIFORM))
  )


(defn get-true-responses
  [^ISurveyResponse sr]
  (try
    (->> (.getResponses sr)
         (remove #(= "q_-1_-1" (.quid (.getQuestion %))))
         (remove nil?))
    (catch Exception e (do (.printStackTrace e)
                           (println sr)))
    )
  )

(defn freetext?
  [^IQuestionResponse qr]
  (.freetext (.getQuestion qr))
  )


(defn make-frequencies
  [responses]
  (reduce #(merge-with (fn [m1 m2] (merge-with + m1 m2)) %1 %2)
          (for [^ISurveyResponse sr responses]
              (apply merge (for [^IQuestionResponse qr (get-true-responses sr)]
                               {(.quid (.getQuestion qr)) (apply merge (for [^Component c (map #(.c ^OptTuple %) (.getOpts qr))]
                                                                 {(.getCid c) 1}
                                                                 )
                                                       )
                                }
                               )
                     )
              )
          )
  )

(defn make-probabilities
  [^Survey s frequencies]
  (assert (every? identity (map map? (vals frequencies))))
  (assert (every? identity (map number? (flatten (map vals (vals frequencies))))))
  (apply merge (for [^Question q (.questions s)]
                 (let [quid (.quid q)
                       ct (reduce + (vals (frequencies (.quid q) {nil 0})))]
                     {quid (apply merge (for [^String cid (keys (.options q))]
                                            {cid (let [freq ((frequencies quid {cid 0}) cid 0)]
                                                     (if (= ct 0) 0.0 (/ freq ct)))
                                             }
                                            )
                                  )
                      }
                     )
                 )
       )
  )

(defn get-path
  [^ISurveyResponse r]
  (set (map #(.block (.getQuestion ^IQuestionResponse %))
          (get-true-responses r)))
  )

(defn make-frequencies-for-paths
  [paths responses]
  (reduce #(merge-with concat %1 %2) (for [^ISurveyResponse r responses]
                                       (apply merge (for [path (seq paths)]
                                                      (if (clojure.set/subset? (set path) (set (get-path r)))
                                                          {path (list r)}
                                                          {}
                                                        )
                                                      )
                                          )
                                       )
          )
  )

(defn get-dag
    [blockList]
    "Takes in a list of Blocks; returns a list of lists of Blocks."
    (if (empty? blockList)
        '(())
        (let [^Block this-block (first blockList)]
            (if-let [branchMap (and (.branchQ this-block) (.branchMap (.branchQ this-block)))]
                (let [dests (set (vals branchMap))
                      blists (map (fn [^Block b] (drop-while #(not= % b) blockList)) (seq dests))]
                    (map #(flatten (cons this-block %)) (map get-dag blists))
                    )
                (map #(flatten (cons this-block %)) (get-dag (rest blockList)))
                )
            )
        )
    )

(defn get-paths
    "Returns paths through **blocks** in the survey. Top level randomized blocks are all listed last"
    [^Survey survey]
    (let [partitioned-blocks (Interpreter/partitionBlocks survey)
          top-level-randomizable-blocks (.get partitioned-blocks true)
          nonrandomizable-blocks (.get partitioned-blocks false)
          dag (get-dag nonrandomizable-blocks)
          ]
        (assert (coll? (first dag)) (type (first dag)))
        (Collections/sort nonrandomizable-blocks)
        (when-not (empty? (flatten dag))
          (assert (= Block (type (ffirst dag))) (str (type (ffirst dag)) " " (.sourceName survey))))
        (map #(concat % top-level-randomizable-blocks) dag)
        )
    )

(defn get-variants
    [^Question q]
    (if (= (.branchParadigm (.block q)) Block$BranchParadigm/ALL)
        (.questions (.block q))
        (list q)
        )
    )

(defn get-equivalent-answer-variants
    "Returns equivalent answer options (a list of survey.Component)"
    [^Question q ^Component c]
    (let [variants (get-variants q)
          offset (- (.getSourceRow q) (.getSourceRow c))
          ]
        (flatten (for [^Question variant variants]
                     (filter #(= (- (.getSourceRow variant) (.getSourceRow %)) offset) (vals (.options variant)))
                     )
                 )
        )
    )

(defn survey-response-contains-answer
    [^ISurveyResponse sr ^Component c]
    (contains? (set (flatten (map (fn [^IQuestionResponse qr] (map #(.c %) (.getOpts qr))) (get-true-responses sr))))
               c
               )
    )

(defn -surveyEntropy
    [^IQCMetrics _ ^Survey s responses]
    (let [paths (set (get-paths s))
          path-map (make-frequencies-for-paths paths responses) ;; map from paths to survey responses
          total-responses (count responses)
          ]
        (->> (for [^Question q (remove #(.freetext %) (.questions s))]
                (for [^Component c (vals (.options q))]
                    (for [path paths]
                        (let [variants (get-equivalent-answer-variants q c) ;; all valid answers (survey.Component) in the answer set
                              responses-this-path (get path-map path) ;; list of survey responses
                              ans-this-path (map (fn [^Component cc]
                                                     (filter #(survey-response-contains-answer % cc) responses-this-path))
                                                 variants)
                              p (/ (count ans-this-path) total-responses)
                          ]
                            (* p (log2 p))))))
            (flatten)
            (reduce +)
            (* -1.0))))

(defn get-questions
    [blockList]
    (if (empty? blockList)
        '()
        (flatten (map (fn [^Block b]
                          (if  (= (.branchParadigm b) Block$BranchParadigm/ALL)
                              (take 1 (shuffle (.questions b))) ;;all options should have the same arity
                              (concat (.questions b) (get-questions (.subBlocks b)))
                              )
                          )
                      blockList)
                 )
        )
    )

(defn max-entropy-one-question
    [^Question q]
    (->> (count (.options q))
         (#(if (= 0 %) 0 (/ 1 %)))
         (#(if (= 0 %) 0 (log2 %)))
         (math/abs)
         )
    )

(defn max-entropy-qlist
    [qlist]
    (reduce + (map max-entropy-one-question qlist)))

(defn get-max-path-for-entropy
    "Returns the path with the highest entropy."
    [bLists]
    (assert (seq? (first bLists)))
    (let [entropies (map max-entropy-qlist (map get-questions bLists))
          pairs (map vector entropies bLists)
          max-ent (apply max entropies)]
        ((first (filter #(= (% 0) max-ent) pairs)) 1)
        )
    )

(defn -getMaxPossibleEntropy
    [^IQCMetrics _ ^Survey s]
    (->> (get-max-path-for-entropy (get-paths s))
         (get-questions)
         (max-entropy-qlist)
         )
    )

(defn pathLength
    [^Survey survey ^PathMetric metric]
    ;;get size of all top level randomizable blocks
    (let [paths (get-paths survey)]
        (cond (= metric PathMetric/MAX) (apply max (map count paths))
              (= metric PathMetric/MIN) (apply min (map count paths))
              :else (throw (Exception. (str "Unknown path metric " metric)))
              )
        )
    )

(defn -minimumPathLength
    [^IQCMetrics _ ^Survey s]
    (pathLength s PathMetric/MIN))

(defn -maximumPathLength
    [^IQCMetrics _ ^Survey s]
    (pathLength s PathMetric/MAX))

(defn -averagePathLength
    [^IQCMetrics _ ^Survey s]
    (let [n 5000]
        (/ (reduce + (map #(count (get-true-responses (.response %))) (getRandomSurveyResponses s n))) n)
        )
    )

(defn -getBasePay
    [^IQCMetrics _ ^Survey s]
    (* (-minimumPathLength s) timePerQuestionInSeconds (/ FEDMINWAGE 3600))
    )

(defn get-prob
    [^IQuestionResponse qr probabilities]
    (map (fn [^OptTuple c]
             (let [quid (.quid (.getQuestion ^IQuestionResponse qr))]
                 (if (= quid Survey/CUSTOM_ID)
                     '(0.0)
                     (get (get probabilities quid) (.getCid (.c c))))
                 )
             )
         (.getOpts qr)
         )
    )

(defn getEntropyForResponse
    [^ISurveyResponse sr probabilities]
  (try
    (->> (get-true-responses sr)
         (map #(get-prob ^IQuestionResponse % probabilities))
         (flatten)
         (remove nil?)
         (reduce +)
         )
    (catch Exception e (do (.printStackTrace e)
                           (println sr)
                           (System/exit 1)))
    )
  )

(defn calculate-entropies
    [responses probabilities]
    (map #(getEntropyForResponse % probabilities) responses)
    )


(defn -calculateBonus
  [^IQCMetrics _ ^ISurveyResponse sr ^Record qc]
    (if (.contains (.validResponses qc) sr)
        (- (* 0.02 (count (get-true-responses sr))) 0.10)
        0.0)
    )

(defn -entropyClassification
    [^IQCMetrics _ ^Survey survey ^ISurveyResponse s responses]
    ;; find outliers in the empirical entropy
  (if (> (count responses) 2)
    (let [probabilities (make-probabilities survey (make-frequencies responses))
          ents (calculate-entropies responses probabilities)
          thisEnt (getEntropyForResponse s probabilities)
          bs-sample (incanter.stats/bootstrap ents incanter.stats/mean)
          p-val (first (incanter.stats/quantile bs-sample :probs [(- 1 @alpha)]))
         ]
      (if (@cutoffs (.sourceName survey))
        (swap! cutoffs assoc (.sourceName survey)  (cons p-val (@cutoffs (.sourceName survey))))
        (swap! cutoffs assoc (.sourceName survey) (list p-val))
        )
      (.setScore s thisEnt)
      (> thisEnt p-val)
      )
    false
    )
  )

(defn -getBotThresholdForSurvey
    [^IQCMetrics _ ^Survey s]
    (@cutoffs (.sourceName s))
    )

(defn -getDag
  [^IQCMetrics _ ^List block-list]
  (get-dag block-list)
  )