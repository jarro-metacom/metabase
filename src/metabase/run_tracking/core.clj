(ns metabase.run-tracking.core
  "Shared heartbeat + orphan-reaping primitives for run-tracking models (transform runs, transform job
  runs, task runs).

  These models all track a long-running operation as a row that is `active` while it runs and gets a
  terminal status when it finishes. A periodic heartbeat stamps a liveness timestamp on the rows whose
  owner is alive; rows whose heartbeat goes stale are presumed orphaned (the owning process died) and are
  swept to a terminal state.

  This namespace owns the two pieces that must be identical and correct everywhere — the atomic stale-row
  reaper and the heartbeat write. Each caller keeps what legitimately differs: its beat *strategy* (an
  in-process registry of run-ids vs a `process_uuid` bulk update) and its *side-effects* (analytics,
  events, notifications, cascades). The reaper returns the pre-update rows so callers can run those
  side-effects on exactly the rows that transitioned."
  (:require
   [metabase.app-db.core :as mdb]
   [metabase.util.honey-sql-2 :as h2x]
   [toucan2.core :as t2])
  (:import
   (java.time Duration Instant OffsetDateTime)
   (java.time.temporal ChronoUnit)))

(set! *warn-on-reflection* true)

(defn cutoff
  "Honeysql form for `(now - age unit)` in the app DB's dialect. Rows whose stale column predates this are
  considered stale."
  [age unit]
  (h2x/add-interval-honeysql-form (mdb/db-type) :%now (- age) unit))

(defn unit->duration
  "Convert `age` of `unit` (`:second`, `:minute`, or `:hour`) to an exact `java.time.Duration`."
  ^Duration [age unit]
  (Duration/of age (case unit
                     :minute ChronoUnit/MINUTES
                     :second ChronoUnit/SECONDS
                     :hour   ChronoUnit/HOURS)))

(defn detection-latency-ms
  "Milliseconds elapsed past `(reference-ts + timeout-duration)` at the time of detection. Clamped at zero."
  [^OffsetDateTime reference-ts ^Duration timeout-duration ^Instant detected-at]
  (max 0 (.toMillis (.minus (Duration/between (.toInstant reference-ts) detected-at)
                            timeout-duration))))

(defn heartbeat-ids!
  "Stamp `heartbeat-column = now` on the still-active rows of `model` named by `ids`. `active` is a
  kv-vector identifying live rows (e.g. `[:is_active true]` or `[:status :started]`); it guards the update
  so a row that has since finished is left untouched. No-op on an empty id list."
  [model active heartbeat-column ids]
  (when (seq ids)
    (apply t2/update! model :id [:in ids] (concat active [{heartbeat-column :%now}]))))

(defn reap-rows!
  "Atomically transition the active rows of `model` whose `stale-column` predates `(now - age unit)` into
  the `terminal` state, returning the pre-update rows (or `nil` when none were stale).

  Runs `SELECT … FOR UPDATE` + `UPDATE` in one transaction so the rows callers report on are exactly the
  rows the UPDATE transitions — without the lock, a concurrent `active`-flip between SELECT and UPDATE
  would leave callers acting on a transition that didn't happen. `UPDATE … RETURNING *` would be simpler
  but isn't portable to H2/MySQL.

  Options:
   - `:model`        toucan model
   - `:active`       kv-vector for live rows, e.g. `[:is_active true]` | `[:status :started]`. Used both as
                     the SELECT/UPDATE guard (via toucan, so keyword↔stored-value transforms apply).
   - `:stale-column` timestamp column compared to the cutoff, e.g. `:last_heartbeat` | `:updated_at` |
                     `:start_time`
   - `:terminal`     map merged into the UPDATE (terminal status, clearing the active flag, end-time,
                     message)
   - `:age` `:unit`  the staleness cutoff for `:stale-column`
   - `:also-stale`   optional honeysql predicate OR'd with `[:< stale-column cutoff]`; the caller builds its
                     own cutoff with [[cutoff]] (e.g. a task-run max-duration bound on `:started_at`)."
  [{:keys [model active stale-column terminal age unit also-stale]}]
  (let [co    (cutoff age unit)
        stale (if also-stale
                [:or [:< stale-column co] also-stale]
                [:< stale-column co])]
    (t2/with-transaction [_conn]
      (when-let [rows (not-empty (apply t2/select model (concat active [{:where stale :for :update}])))]
        (apply t2/update! model :id [:in (mapv :id rows)] (concat active [terminal]))
        rows))))
