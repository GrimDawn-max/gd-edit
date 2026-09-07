(ns gd-edit.gt-character-spec
  (:require [clojure.spec.alpha :as s]))

;; --------------
;;   itemSkills
;; --------------
(s/def :gt-char/itemSkills
  (s/coll-of clojure.core/any?))


;; ----------
;;   Skills
;; ----------
(s/def :gt-char/autoCastSkill clojure.core/string?)
(s/def :gt-char/level clojure.core/integer?)
(s/def :gt-char/name clojure.core/string?)
(s/def :gt-char/skills
  (s/coll-of
    (s/keys
    :req-un
    [:gt-char/level :gt-char/name]
    :opt-un
    [:gt-char/autoCastSkill])))

 (s/def :gt-char/waist
  (s/keys
   :req-un
   [:gt-char/augment
    :gt-char/component
    :gt-char/item
    :gt-char/prefix
    :gt-char/suffix]))



;; ---------
;;   Item
;; ---------
(s/def :gt-item/item string?)
(s/def :gt-item/prefix string?)
(s/def :gt-item/suffix string?)
(s/def :gt-item/component string?)
(s/def :gt-item/augment string?)
(s/def :gt-item/relicBonus string?)
(s/def :gt-item/ascendedAffix string?)
(s/def :gt-char/item (s/keys :opt-un [:gt-item/item :gt-item/prefix :gt-item/suffix :gt-item/component :gt-item/augment :gt-item/relicBonus
                                    :gt-item/ascendedAffix]))

;; -------------
;;   Equipment
;; -------------
(s/def :gt-equipment/relic :gt-char/item)
(s/def :gt-equipment/ring2 :gt-char/item)
(s/def :gt-equipment/medal :gt-char/item)
(s/def :gt-equipment/amulet :gt-char/item)
(s/def :gt-equipment/hands :gt-char/item)
(s/def :gt-equipment/head :gt-char/item)
(s/def :gt-equipment/shoulders :gt-char/item)
(s/def :gt-equipment/chest :gt-char/item)
(s/def :gt-equipment/weapon1Alt :gt-char/item)
(s/def :gt-equipment/weapon1 :gt-char/item)
(s/def :gt-equipment/legs :gt-char/item)
(s/def :gt-equipment/feet :gt-char/item)
(s/def :gt-equipment/waist :gt-char/item)
(s/def :gt-equipment/ring1 :gt-char/item)
(s/def :gt-equipment/weapon2 :gt-char/item)
(s/def :gt-equipment/weapon2Alt :gt-char/item)

(s/def :gt-char/equipment-slots #{:head :shoulders :legs :hands :feet :waist :ring1 :ring2 :relic :medal :amulet :chest :weapon1 :weapon2 :weapon1Alt :weapon2Alt})
(s/def :gt-char/equipment-list (s/map-of :gt-char/equipment-slots :gt-char/item))


;; -------
;;   bio
;; -------
(s/def :gt-char/spirit clojure.core/integer?)
(s/def :gt-char/cunning clojure.core/integer?)
(s/def :gt-char/physique clojure.core/integer?)
(s/def :gt-char/devotionPoints clojure.core/integer?)
(s/def :gt-char/skillPoints clojure.core/integer?)
(s/def :gt-char/attributePoints clojure.core/integer?)

(s/def :gt-char/bio
  (s/keys
   :req-un
   [:gt-char/attributePoints
    :gt-char/cunning
    :gt-char/devotionPoints
    :gt-char/level
    :gt-char/physique
    :gt-char/skillPoints
    :gt-char/spirit]))


(s/def :gt-char/data
  (s/keys
   :req-un
   [:gt-char/bio :gt-char/equipment :gt-char/itemSkills :gt-char/skills]))

(s/def :gt-char/created_for_build string?)

(s/def :gt-char/root
  (s/keys
   :req-un
   [:gt-char/data :gt-char/created_for_build]))
