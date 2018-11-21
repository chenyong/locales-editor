
(ns app.server
  (:require [app.schema :as schema]
            [app.service :refer [run-server! sync-clients!]]
            [app.updater :refer [updater]]
            [cljs.reader :refer [read-string]]
            [app.reel :refer [reel-reducer refresh-reel reel-schema]]
            ["fs" :as fs]
            ["shortid" :as shortid]
            ["child_process" :as cp]
            ["path" :as path]
            [app.node-config :as node-config]
            [app.config :refer [dev?]]
            [app.config :as config]
            [fipp.edn :refer [pprint]]
            [clojure.string :as string]
            [favored-edn.core :refer [write-edn]]
            ["javascript-natural-sort" :as naturalSort]
            ["latest-version" :as latest-version]
            ["chalk" :as chalk])
  (:require-macros [clojure.core.strint :refer [<<]]))

(def initial-db
  (let [filepath (:storage-path node-config/env)]
    (if (fs/existsSync filepath)
      (do
       (println "Found storage in:" (:storage-path node-config/env))
       (let [storage (read-string (fs/readFileSync filepath "utf8"))
             schema-version (get-in storage [:schema :version])
             cli-version (get-in schema/database [:schema :version])]
         (when (not= schema-version cli-version)
           (println
            (<<
             "Schema version(~{schema-version}) does not match version of cli(~{cli-version}). Existing!"))
           (.exit js/process 1))
         storage))
      schema/database)))

(defonce *reel (atom (merge reel-schema {:base initial-db, :db initial-db})))

(defonce *reader-reel (atom @*reel))

(defn check-version! []
  (let [pkg (.parse js/JSON (fs/readFileSync (path/join js/__dirname "../package.json")))
        version (.-version pkg)]
    (-> (latest-version (.-name pkg))
        (.then
         (fn [npm-version]
           (if (= npm-version version)
             (println "Running latest version" version)
             (println
              (.yellow
               chalk
               (<< "New version ~{npm-version} available, current one is ~{version} .")))))))))

(defn lines-sorter [a b]
  (set! (.-insensitive naturalSort) true)
  (if (= (string/lower-case a) (string/lower-case b)) (compare a b) (naturalSort a b)))

(defn get-local-file [locales lang]
  (let [locale-keys (keys locales)]
    (->> locale-keys
         (map
          (fn [k]
            (let [v (get-in locales [k lang])]
              (str "  " k ": " (if (string/includes? v "\"") (str "'" v "'") (pr-str v)) ","))))
         (sort lines-sorter)
         (string/join "\n"))))

(defn persist-db! []
  (let [file-content (write-edn (assoc (:db @*reel) :sessions {}))
        now (js/Date.)
        storage-path (:storage-path node-config/env)
        backup-path (path/join
                     js/__dirname
                     "backups"
                     (str (inc (.getMonth now)))
                     (str (.getDate now) "-storage.edn"))]
    (fs/writeFileSync storage-path file-content)
    (cp/execSync (str "mkdir -p " (path/dirname backup-path)))
    (fs/writeFileSync backup-path file-content)
    (println "Saved file in" storage-path "and saved backup in" backup-path)))

(defn generate-files! []
  (let [base js/process.env.PWD
        en-file (.join path base "enUS.ts")
        zh-file (.join path base "zhCN.ts")
        interface-file (.join path base "interface.ts")
        db (:db @*reel)
        locales (:locales db)]
    (println "Genrate files.")
    (fs/writeFileSync
     en-file
     (str
      "import { ILang } from \"./interface\";\nexport const enUS: ILang = {\n"
      (get-local-file locales "enUS")
      "\n};\n"))
    (fs/writeFileSync
     zh-file
     (str
      "import { ILang } from \"./interface\";\nexport const zhCN: ILang = {\n"
      (get-local-file locales "zhCN")
      "\n};\n"))
    (fs/writeFileSync
     interface-file
     (let [locale-keys (keys locales)]
       (str
        "export interface ILang {\n"
        (->> locale-keys
             (map (fn [k] (str "  " k ": string;")))
             (sort lines-sorter)
             (string/join "\n"))
        "\n}\n")))
    (persist-db!)))

(defn dispatch! [op op-data sid]
  (let [op-id (.generate shortid), op-time (.valueOf (js/Date.))]
    (if dev? (println "Dispatch!" (str op) op-data sid))
    (try
     (cond
       (= op :effect/persist) (persist-db!)
       (= op :effect/codegen) (generate-files!)
       :else
         (let [new-reel (reel-reducer @*reel updater op op-data sid op-id op-time)]
           (reset! *reel new-reel)))
     (catch js/Error error (.error js/console error)))))

(defn on-exit! [code]
  (persist-db!)
  (println "exit code is:" (pr-str code))
  (.exit js/process))

(defn render-loop! []
  (if (not (identical? @*reader-reel @*reel))
    (do (reset! *reader-reel @*reel) (sync-clients! @*reader-reel)))
  (js/setTimeout render-loop! 200))

(defn main! []
  (run-server! #(dispatch! %1 %2 %3) (:port config/site))
  (render-loop!)
  (.on js/process "SIGINT" on-exit!)
  (js/setInterval #(persist-db!) (* 60 1000 10))
  (println
   "Server started. Open editer on"
   (.blue chalk "http://repo.tiye.me/chenyong/locales-editor/"))
  (check-version!))

(defn reload! []
  (println "Code updated.")
  (reset! *reel (refresh-reel @*reel initial-db updater))
  (sync-clients! @*reader-reel))