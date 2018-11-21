
(ns app.twig.container
  (:require [recollect.macros :refer [deftwig]]
            [app.twig.user :refer [twig-user]]
            ["randomcolor" :as color]
            [clojure.string :as string]))

(defn find-chunk [xs x] (string/includes? (string/lower-case xs) (string/lower-case x)))

(deftwig
 twig-members
 (sessions users)
 (->> sessions
      (map (fn [[k session]] [k (get-in users [(:user-id session) :name])]))
      (into {})))

(deftwig
 twig-container
 (db session records)
 (let [logged-in? (some? (:user-id session))
       router (:router session)
       matched-locale-pairs (->> (:locales db)
                                 (filter
                                  (fn [[k info]]
                                    (if (some? (:query session))
                                      (or (find-chunk k (:query session))
                                          (find-chunk (get info "zhCN") (:query session))
                                          (find-chunk (get info "enUS") (:query session)))
                                      true))))
       base-data {:logged-in? logged-in?,
                  :session session,
                  :reel-length (count records),
                  :locales (->> matched-locale-pairs
                                (sort-by (fn [[k info]] (count k)))
                                (take 40)
                                (into {})),
                  :matched-count (count matched-locale-pairs)}]
   (merge
    base-data
    (if logged-in?
      {:user (twig-user (get-in db [:users (:user-id session)])),
       :router (assoc
                router
                :data
                (case (:name router)
                  :home (:pages db)
                  :profile (twig-members (:sessions db) (:users db))
                  {})),
       :count (count (:sessions db)),
       :color (color/randomColor)}
      nil))))