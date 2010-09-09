;   Copyright (c) Chris Houser, Dec 2008. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Common Public License 1.0 (http://opensource.org/licenses/cpl.php)
;   which can be found in the file CPL.TXT at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

; Utilities meant to be used interactively at the REPL

(ns
  #^{:author "Chris Houser, Christophe Grand, Stephen Gilardi, Michel Salim, Christophe Grande"
     :doc "Utilities meant to be used interactively at the REPL"}
  clojure.repl
  (:import (java.io LineNumberReader InputStreamReader PushbackReader)
           (clojure.lang RT Reflector)))

;; ----------------------------------------------------------------------
;; Examine Clojure functions (Vars, really)

(defn source-fn
  "Returns a string of the source code for the given symbol, if it can
  find it.  This requires that the symbol resolve to a Var defined in
  a namespace for which the .clj is in the classpath.  Returns nil if
  it can't find the source.  For most REPL usage, 'source' is more
  convenient.

  Example: (source-fn 'filter)"
  [x]
  (when-let [v (resolve x)]
    (when-let [filepath (:file (meta v))]
      (when-let [strm (.getResourceAsStream (RT/baseLoader) filepath)]
        (with-open [rdr (LineNumberReader. (InputStreamReader. strm))]
          (dotimes [_ (dec (:line (meta v)))] (.readLine rdr))
          (let [text (StringBuilder.)
                pbr (proxy [PushbackReader] [rdr]
                      (read [] (let [i (proxy-super read)]
                                 (.append text (char i))
                                 i)))]
            (read (PushbackReader. pbr))
            (str text)))))))

(defmacro source
  "Prints the source code for the given symbol, if it can find it.
  This requires that the symbol resolve to a Var defined in a
  namespace for which the .clj is in the classpath.

  Example: (source filter)"
  [n]
  `(println (or (source-fn '~n) (str "Source not found"))))

(defn apropos
  "Given a regular expression or stringable thing, return a seq of
all definitions in all currently-loaded namespaces that match the
str-or-pattern."
  [str-or-pattern]
  (let [matches? (if (instance? java.util.regex.Pattern str-or-pattern)
                   #(re-find str-or-pattern (str %))
                   #(.contains (str %) (str str-or-pattern)))]
    (mapcat (fn [ns]
              (filter matches? (keys (ns-publics ns))))
            (all-ns))))

(defn dir-fn
  "Returns a sorted seq of symbols naming public vars in
  a namespace"
  [ns]
  (sort (map first (ns-publics (the-ns ns)))))

(defmacro dir
  "Prints a sorted directory of public vars in a namespace"
  [nsname]
  `(doseq [v# (dir-fn '~nsname)]
     (println v#)))

(def ^:private demunge-map
  (into {"$" "/"} (map (fn [[k v]] [v k]) clojure.lang.Compiler/CHAR_MAP)))

(def ^:private demunge-pattern
  (re-pattern (apply str (interpose "|" (map #(str "\\Q" % "\\E")
                                             (keys demunge-map))))))

(defn- re-replace [re s f]
  (let [m (re-matcher re s)
        mseq (take-while identity
                         (repeatedly #(when (re-find m)
                                        [(re-groups m) (.start m) (.end m)])))]
    (apply str
           (concat
             (mapcat (fn [[_ _ start] [groups end]]
                       (if end
                         [(subs s start end) (f groups)]
                         [(subs s start)]))
                     (cons [0 0 0] mseq)
                     (concat mseq [nil]))))))

(defn demunge
  "Given a string representation of a fn class,
  as in a stack trace element, returns a readable version."
  {:added "1.3"}
  [fn-name]
  (re-replace demunge-pattern fn-name demunge-map))

(defn root-cause
  "Returns the initial cause of an exception or error by peeling off all of
  its wrappers"
  {:added "1.3"}
  [^Throwable t]
  (loop [cause t]
    (if-let [cause (.getCause cause)]
      (recur cause)
      cause)))

(defn stack-element-str
  "Returns a (possibly unmunged) string representation of a StackTraceElement"
  {:added "1.3"}
  [^StackTraceElement el]
  (let [file (.getFileName el)
        clojure-fn? (and file (or (.endsWith file ".clj")
                                  (= file "NO_SOURCE_FILE")))]
    (str (if clojure-fn?
           (demunge (.getClassName el))
           (str (.getClassName el) "." (.getMethodName el)))
         " (" (.getFileName el) ":" (.getLineNumber el) ")")))

(defn pst
  "Prints a stack trace of the exception. If none supplied, uses the root cause of the
  most recent repl exception (*e)."
  {:added "1.3"}
  ([]
     (when-let [e *e]
       (pst (root-cause e))))
  ([e]
     (.println *err* (.getMessage e))
     (doseq [el (.getStackTrace e)]
       (when-not (#{"clojure.lang.RestFn" "clojure.lang.AFn"} (.getClassName el))
         (.println *err* (str \tab (stack-element-str el)))))
     (when (.getCause e)
       (.println *err* "Caused by:")
       (pst (.getCause e)))))

