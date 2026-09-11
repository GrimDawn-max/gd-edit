// The character sheet, drawn from the data gd-edit wrote into this page.
//
// This is a transliteration of the renderer the sheet was prototyped with, kept
// deliberately close to it so the two can be compared line for line. It reads
// one global, DATA, and builds the whole page into the document.
//
// Everything the page needs is already here: the art is embedded as data URIs
// and nothing is ever fetched.
(function () {
  "use strict";
  var D = window.DATA;
  var d = D.sheet, tree = D.tree, devo = D.devo, buffs = D.buffs;
  var icons = D.icons || {}, namemap = D.namemap || {}, resico = D.resicons || {};
  var treeico = D.treeicons || {}, compico = D.compicons || {};
  var c = d.character, st = d.stats, si = d.statInfo || {};
  var TIPS = {};

  var RAR = {Legendary: "#d08a2c", Epic: "#8e5fc0", Rare: "#4a9c4a",
             Common: "#9a9a9a", Magical: "#4a82c0"};
  // the affinity each completed constellation grants. Colours are the game's:
  // Ascendant violet, Chaos red, Eldritch green, Order gold, Primordial blue.
  var AFF = {Ascendant: "#a678d8", Chaos: "#c4453a", Eldritch: "#5fa845",
             Order: "#c9a12f", Primordial: "#4f86c6"};

  function e(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#x27;");
  }
  // thousands separators, the way the prototype's ":," did
  function n(v) { return Number(v).toLocaleString("en-US"); }
  // drops a trailing .0, as "%g" does: 15 rather than 15.0
  function g(v) { return String(Number(v)); }
  // a stable id for a hover target. Any scheme does, so long as the same input
  // always gives the same key within one page.
  function key(prefix, s) {
    var h = 2166136261;
    for (var i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = (h * 16777619) >>> 0;
    }
    return prefix + (h % 999999);
  }

  // ------------------------------------------------------------- item lines
  var ANSI = /\x1b\[[0-9;]*m/g;

  // gd-edit paints the values yellow with ANSI, the way the game highlights the
  // numbers and leaves the words plain, so the codes are structure rather than
  // noise: they mark exactly the spans to emphasise. They nest, hence the depth
  // count instead of a plain replace. Leading spaces are the summary's own
  // indentation for nested blocks -- a granted skill inside a component -- and
  // become a left margin, since HTML would otherwise collapse them.
  function gline(s) {
    var ind = s.length - s.replace(/^ +/, "").length;
    var out = [], depth = 0, pos = 0, m;
    ANSI.lastIndex = 0;
    while ((m = ANSI.exec(s)) !== null) {
      var chunk = s.slice(pos, m.index);
      if (chunk) out.push(depth ? "<b>" + e(chunk) + "</b>" : e(chunk));
      depth = m[0] !== "\x1b[m" ? depth + 1 : Math.max(0, depth - 1);
      pos = m.index + m[0].length;
    }
    var tail = s.slice(pos);
    if (tail) out.push(depth ? "<b>" + e(tail) + "</b>" : e(tail));
    var body = out.join("").trim();
    var style = ind ? ' style="margin-left:' + (ind * 11) + 'px"' : "";
    // a blank line is the summary's paragraph break inside a block
    return body ? '<div class="gl"' + style + ">" + body + "</div>"
                : '<div class="gsp"></div>';
  }

  // The item as the game presents it: its own stats first, then each thing
  // attached to it under its own heading, then what it costs to wear.
  function tipItem(it) {
    var blocks = (it.groups || []).map(function (grp) {
      var head = "";
      if (grp.heading) {
        // an augment carries its own rarity colour, the way the game colours
        // it; a component is gold whatever its rarity
        var col = RAR[grp.rarity] ? ' style="color:' + RAR[grp.rarity] + '"' : "";
        head = '<div class="gh ' + grp.kind + '"' + col + ">" + e(grp.heading) + "</div>";
        if (grp.sub) head += '<div class="gsub">' + e(grp.sub) + "</div>";
      }
      var mem = "";
      if (grp.members && grp.members.length) {
        mem = '<div class="gmem">' + grp.members.map(function (m) {
          return '<span class="' + (m.worn ? "w" : "u") + (m["this"] ? " me" : "") +
                 '">' + e(m.name) + "</span>";
        }).join("") + "</div>";
      }
      return '<div class="grp ' + grp.kind + '">' + head + mem +
             grp.lines.map(gline).join("") + "</div>";
    }).join("");
    var req = (it.requires || []).map(gline).join("");
    if (it.itemLevel != null) req += gline("Item Level: " + it.itemLevel);
    // no seed and no "n/n at max" here: the seed is not something the game shows
    // on an item, and the paper doll already marks a fully-maxed piece
    return '<div class="itip" data-wide>' +
      '<h5 style="color:' + (RAR[it.rarity] || "#9a9a9a") + '">' + e(it.name) + "</h5>" +
      '<div class="tm">' + e(it.slot) + " · " + e(it.kind || it.rarity) + "</div>" +
      (it.primary || []).map(gline).join("") + blocks +
      (req ? '<div class="greq">' + req + "</div>" : "") + "</div>";
  }

  // ------------------------------------------------------------ skill lines
  // How the game words a devotion trigger. The record name is the condition run
  // together -- "selfonanyhit" -- and reads as a typo unless it is spelled out.
  var TRIGGERS = {
    attack: "on Attack", anyhit: "on any Hit", hit: "when Hit",
    crit: "on Critical Hit", critanyhit: "on any Critical Hit",
    block: "on Block", lowhealth: "when Health is low", kill: "on Kill",
    hitbymelee: "when Hit in melee", hitbyprojectile: "when Hit by a projectile"
  };

  // the game's "Current Level : 10 + 2" -- points spent, then what gear adds
  function lvlhead(label, pts, gear) {
    return '<div class="lvh">' + label + " : <s>" + pts + "</s>" +
           (gear ? ' <u>+ ' + gear + "</u>" : "") + "</div>";
  }

  // A skill as the game's own tooltip shows it: what it does now, what the next
  // point buys, and the devotion power bound to it.
  function tipNode(nd) {
    var over = nd.level > nd.max;
    var out = ['<h5 class="skn">' + e(nd.name) + "</h5>"];
    if (nd.desc) out.push('<div class="rdesc">' + e(nd.desc) + "</div>");
    if (nd.cur && nd.cur.length) {
      out.push('<div class="lvb">', lvlhead("Current Level", nd.level, nd.gear || 0));
      out.push(nd.cur.map(gline).join(""), "</div>");
    } else {
      out.push('<div class="tm">' + nd.level + " of " + nd.max +
               (over ? " — above its cap, from gear" : "") + "</div>");
    }
    if (nd.next && nd.next.length) {
      out.push('<div class="lvb next">', lvlhead("Next Level", nd.level + 1, nd.gear || 0));
      out.push(nd.next.map(gline).join(""), "</div>");
    }
    var cel = nd.celestial;
    if (cel && cel.name) {
      // the controller record is named like "cast_@enemyonattack_15%"; the
      // chance comes off it separately, so only the condition is wanted here
      var raw = String(cel.trigger || "").replace(/_?\d+%$/, "").toLowerCase()
                  .replace(/^(enemy|self)on/, "");
      var when = TRIGGERS[raw] || ("on " + raw);
      var bits = [];
      if (cel.chance != null) bits.push(g(cel.chance) + "% Chance");
      if (when) bits.push(when);
      out.push('<div class="lvb cel"><div class="lvh">Celestial Power:</div>' +
               '<div class="celn">' + e(cel.name) +
               (bits.length ? ' <i>(' + e(bits.join(" ")) + ")</i>" : "") + "</div>");
      out.push((cel.lines || []).map(gline).join(""), "</div>");
    }
    return out.join("");
  }

  // A constellation the way the game presents it: what every star adds up to,
  // what it costs in affinity, and what completing it pays back.
  function tipConst(k) {
    var out = ['<h5 class="skn">' + e(k.name) + "</h5>"];
    if (k.desc) out.push('<div class="rdesc">' + e(k.desc) + "</div>");
    out.push('<div class="tm">' + k.starsTaken + " of " + k.starsTotal + " stars" +
             (k.complete ? " · complete" : "") + "</div>");
    if (k.total && k.total.length)
      out.push('<div class="lvb"><div class="lvh">Total</div>' +
               k.total.map(gline).join("") + "</div>");
    if (k.petTotal && k.petTotal.length)
      out.push('<div class="lvb"><div class="lvh">Bonus to All Pets</div>' +
               k.petTotal.map(gline).join("") + "</div>");
    // the same dot the devotion bar uses, so an affinity reads the same colour
    // wherever it appears; the count stays white against it
    function aff(title, xs) {
      if (!xs || !xs.length) return "";
      return '<div class="lvb"><div class="lvh">' + title + "</div>" +
        xs.map(function (a) {
          return '<div class="afr"><span class="afd" style="background:' +
                 (AFF[a.name] || "#888") + '"></span><b>' + a.points + "</b><i>" +
                 e(a.name) + "</i></div>";
        }).join("") + "</div>";
    }
    out.push(aff("Affinity Requirement", k.required));
    out.push(aff("Complete Constellation Bonus", k.affinity));
    return out.join("");
  }

  // ------------------------------------------------------------- paper doll
  function aid(it) { return key("i", it.name + it.seed); }
  function ico(it) { return icons[namemap[it.name] || ""] || ""; }

  var bySlot = {};
  d.equipment.forEach(function (it) {
    (bySlot[it.slot] = bySlot[it.slot] || []).push(it);
  });

  function cell(slot, idx) {
    var lst = bySlot[slot] || [], it = lst[idx || 0];
    if (!it) return '<div class="dcell empty"><div class="dbox"></div><span>' +
                    e(slot) + "</span></div>";
    var ic = ico(it);
    var img = ic ? '<img src="' + ic + '" alt="">' : '<div class="dnone"></div>';
    // the game's inventory badges a piece that has a component fitted
    var cim = compico[it.name];
    if (cim) img += '<img class="cbadge" src="' + cim + '" alt="">';
    var nmax = it.stats.filter(function (s) { return s.max; }).length;
    var nse = it.stats.filter(function (s) { return s.searchable; }).length;
    var full = (nse && nmax === nse) ? " full" : "";
    // the green outline already says every rollable stat is at its ceiling, so
    // repeating it as "4/4 at max" says nothing; the count earns its place only
    // where the item fell short
    var sub = (nse && !full) ? nmax + "/" + nse + " at max" : it.rarity;
    TIPS[aid(it)] = tipItem(it);
    return '<div class="dcell' + full + '" data-tip="' + aid(it) + '">' +
           '<div class="dbox">' + img + "</div><span style=\"color:" +
           (RAR[it.rarity] || "#9a9a9a") + '">' + e(it.name) +
           "<i>" + e(sub) + "</i></span></div>";
  }

  // --------------------------------------------------------------- stat rows
  // One stat line, with the game's own description behind it on hover. The
  // heading shows the base and what gear adds, the way the game prints
  // "Physique 754 + 400".
  function srow(label, value, note, k, extra, table) {
    var info = k ? si[k] : null;
    var tip = "";
    if (info) {
      var sid = "st" + k;
      var split = (si.split || {})[k];
      var head = split ? "<s>" + n(split[0]) + "</s> <u>+ " + n(split[1]) + "</u>"
                       : "<s>" + value + "</s>";
      var rows = (extra || []).filter(function (p) { return p[1]; })
        .map(function (p) {
          return '<div class="tx"><i>' + e(p[0]) + "</i>" + e(String(p[1])) + "</div>";
        }).join("");
      TIPS[sid] = "<h5>" + e(label) + " " + head + "</h5>" +
                  (info.desc ? '<div class="rdesc">' + e(info.desc) + "</div>" : "") +
                  (table || "") + rows;
      tip = ' data-tip="' + sid + '"';
    }
    return '<div class="srow"' + tip + "><span>" + e(label) + "</span><b>" + value +
           (note ? "<i>" + e(note) + "</i>" : "") + "</b></div>";
  }

  var p_ = si.physique || {}, c_ = si.cunning || {}, s_ = si.spirit || {};
  var attrbox = '<div class="cbox"><h4>Attributes</h4>' +
    srow("Physique", st.physique, "", "physique",
         [["Bonus Health", n(p_.bonusHealth || 0)], ["Bonus Health Regen", p_.bonusRegen]]) +
    srow("Cunning", st.cunning, "", "cunning",
         [["Bonus Health", n(c_.bonusHealth || 0)]]) +
    srow("Spirit", st.spirit, "", "spirit",
         [["Bonus Health", n(s_.bonusHealth || 0)], ["Bonus Energy", n(s_.bonusEnergy || 0)]]) +
    srow("Health", n(st.health), " / " + n(st.health), "health") +
    // the game shows energy as current/max; the reserved part is held back by
    // toggled buffs and never refills, so both numbers are worth showing
    srow("Energy", n(st.energyUsable), " / " + n(st.energy), "energy",
         [["Reserved by buffs", n((si.energy || {}).reserved || 0)]]) +
    "</div>";

  // the armour hover carries the per-slot breakdown the game shows, with the
  // hit chance each area is weighted by
  var SLOTS = [["Head", 15], ["Shoulders", 15], ["Chest", 26],
               ["Arms", 12], ["Legs", 20], ["Feet", 12]];
  var absorb = (si.armour || {}).absorption || 0;
  var armourTable = '<table class="artab" data-wide><tr class="hd"><td></td>' +
    "<td>Chance to Hit Area</td><td>Armor Rating</td><td>Armor Absorption</td></tr>" +
    SLOTS.map(function (s) {
      return "<tr><td>" + s[0] + '</td><td class="v">' + s[1] + '%</td><td class="v">' +
             n(st.armourBySlot[s[0]] || 0) + '</td><td class="v">' + absorb + "%</td></tr>";
    }).join("") + "</table>";

  var combatbox = '<div class="cbox"><h4>Combat Stats<span class="ast">*</span></h4>' +
    srow("Offensive Ability", n(st.offensiveAbility), "", "oa") +
    srow("Defensive Ability", n(st.defensiveAbility), "", "da") +
    srow("Armor Rating", n(st.armour), "", "armour", null, armourTable) +
    "</div>";

  // ------------------------------------------------------------------ buffs
  function buffrow(b) {
    var t = (treeico[String(b.tex || "").toLowerCase().replace(/^ui\//, "")] || {}).uri || "";
    var img = t ? '<img src="' + t + '" alt="">' : '<span class="ignone"></span>';
    var bid = key("b", b.name + b.source + b.boundTo);
    var bits = [];
    if (b.duration) bits.push(g(b.duration) + "s");
    if (b.trigger) bits.push(b.trigger);
    if (b.boundTo) bits.push("via " + b.boundTo);
    TIPS[bid] = "<h5>" + e(b.name) + '</h5><div class="tm">' + e(b.source) + "</div>" +
      bits.map(function (x) { return '<div class="tx">' + e(x) + "</div>"; }).join("");
    return '<div class="bf' + (b.on ? " on" : "") + '" data-tip="' + bid + '">' + img +
           "<span><b>" + e(b.name) + "</b><i>" + e(b.source) + "</i></span></div>";
  }
  function buffgroup(title, items) {
    if (!items || !items.length) return "";
    return '<div class="bgrp"><h5>' + e(title) + " · " + items.length + "</h5>" +
           items.map(buffrow).join("") + "</div>";
  }
  var onCount = 0, allCount = 0;
  Object.keys(buffs).forEach(function (k) {
    allCount += buffs[k].length;
    onCount += buffs[k].filter(function (b) { return b.on; }).length;
  });
  // these run continuously and reserve energy -- the auras, in the sense the
  // game's own buff bar shows them
  TIPS.buffs = '<div class="bufftip" data-wide>' +
    buffgroup("Auras", buffs.permanent) +
    buffgroup("Activated", buffs.activated) +
    buffgroup("Triggered", buffs.triggered) + "</div>";
  // The game reduces this to a single button and shows the list on demand; a
  // tile does the same here, which keeps the row free for whatever comes next.
  var buffbox = '<div class="tiles"><div class="tile" data-tip="buffs"><b>Buffs</b>' +
                "<span>" + onCount + " <em>/ " + allCount + "</em></span></div></div>";

  // ------------------------------------------------------------ resistances
  function rcell(r) {
    var ic = resico[r.field] || "";
    var img = ic ? '<img src="' + ic + '" alt="" width="24" height="24">'
                 : '<span class="rno"></span>';
    // the overcap moves to the hover, where the game puts it, with the game's
    // own wording and its description of what the resistance protects against
    var rid = "r" + r.field;
    TIPS[rid] = "<h5>" + e(r.label) + " <s>" + r.shown + "%</s>" +
                (r.over ? ' <u>(+' + r.over + "% Over Maximum)</u>" : "") + "</h5>" +
                (r.desc ? '<div class="rdesc">' + e(r.desc) + "</div>" : "");
    return '<div class="rcell' + (r.over ? " capped" : "") + '" data-tip="' + rid + '">' +
           img + "<b>" + r.shown + "%</b></div>";
  }
  // in the order the game lists them
  var resbox = '<div class="cbox res"><h4>Resistances</h4><div class="rgrid">' +
               d.resistances.map(rcell).join("") + "</div></div>";

  // ----------------------------------------------------------------- skills
  // One taken skill: its own icon, its name, and the level the game reads it
  // at. The game shows the effective level over the cap -- points spent plus
  // what gear adds -- not the points alone, so Summon Hellhound reads 26 / 16
  // on one point spent. The tooltip breaks the two apart.
  function skillcell(nd) {
    var eff = nd.level + (nd.gear || 0);
    var t = (treeico[String(nd.tex || "").toLowerCase().replace(/^ui\//, "")] || {}).uri || "";
    var img = t ? '<img src="' + t + '" alt="">' : '<span class="ignone"></span>';
    var tid = key("s", nd.name + nd.x + nd.y);
    TIPS[tid] = tipNode(nd);
    // red marks a skill whose bonuses run past its ceiling and are cut, not one
    // that merely reaches it: Briarthorn totals exactly 26 and is blue in game,
    // Hellhound totals 27, shows 26, and is red. Over its cap is ordinary and
    // takes no styling.
    var cls = nd.pinned ? "cap" : "";
    return '<div class="igc" data-tip="' + tid + '"><div class="igbox">' + img +
           "</div><span><b>" + e(nd.name) + '</b><i class="' + cls + '">' +
           eff + " / " + nd.max + "</i></span></div>";
  }

  function treeBlock(t) {
    // only what was actually taken, in the order the tree reads: down, then across
    var taken = t.nodes.filter(function (x) { return x.level > 0; })
                       .sort(function (a, b) { return a.y - b.y || a.x - b.x; });
    var spent = taken.reduce(function (s, x) { return s + x.level; }, 0);
    // columns sized to their content rather than split evenly, so a long name
    // like "Summon Briarthorn" takes the width it needs and stays on one line.
    // Beyond eight they would be too narrow and are better left to wrap.
    var cols = (taken.length > 0 && taken.length <= 8)
      ? "grid-template-columns:repeat(" + taken.length + ",auto);justify-content:space-between"
      : "grid-template-columns:repeat(auto-fill,minmax(280px,1fr))";
    return "<section><h2>" + e(t.mastery) + " · " + spent + " points</h2>" +
           '<div class="ig" style="' + cols + '">' + taken.map(skillcell).join("") +
           "</div></section>";
  }
  var skilltrees = tree.trees.map(treeBlock).join("");

  // -------------------------------------------------------------- devotions
  function affinityBar() {
    return '<div class="affs">' + devo.affinities.map(function (a) {
      return '<div class="afc"><span class="afd" style="background:' +
             (AFF[a.name] || "#888") + '"></span><b>' + a.points + '</b><span class="afn">' +
             e(a.name) + "</span></div>";
    }).join("") + "</div>";
  }

  // A devotion star's recorded position is the top-left of its sprite, and that
  // sprite is 64x64, so its centre is the position plus 32. Without this every
  // star sits up and to the left of where the game draws it -- most visibly on
  // Crossroads, whose five points are a single compass rose.
  var STAR_HALF = 32;

  function constRow(k) {
    var art = treeico["constellation:" + k.name];
    var img;
    if (art && k.bgX != null) {
      // a star's position and the art's position are both in the game's map
      // coordinates, so the offset between them is its place on the art
      var at = function (s) {
        return [(s.x + STAR_HALF - k.bgX) / art.ow * 100,
                (s.y + STAR_HALF - k.bgY) / art.oh * 100];
      };
      var dots = k.stars.map(function (s) {
        var xy = at(s);
        return '<b class="dot' + (s.taken ? " on" : "") + (s.skill ? " sk" : "") +
               '" style="left:' + xy[0].toFixed(2) + "%;top:" + xy[1].toFixed(2) + '%"></b>';
      }).join("");
      // the game joins its stars; devotionLinksN names the button N joins to
      var pos = {}, taken = {};
      k.stars.forEach(function (s) { pos[s.button] = at(s); taken[s.button] = s.taken; });
      var lines = (k.links || []).filter(function (l) {
        return pos[l[0]] && pos[l[1]];
      }).map(function (l) {
        var a = pos[l[0]], b = pos[l[1]];
        return '<line x1="' + a[0].toFixed(2) + '" y1="' + a[1].toFixed(2) +
               '" x2="' + b[0].toFixed(2) + '" y2="' + b[1].toFixed(2) + '"' +
               (taken[l[0]] && taken[l[1]] ? ' class="on"' : "") + "/>";
      }).join("");
      var svg = lines ? '<svg class="clink" viewBox="0 0 100 100" ' +
                        'preserveAspectRatio="none">' + lines + "</svg>" : "";
      img = '<span class="plot" style="width:' + art.w + "px;height:" + art.h + 'px">' +
            '<img src="' + art.uri + '" alt="">' + svg + dots + "</span>";
    } else if (art) {
      // Crossroads has five records, one per affinity, and only one carries a
      // background -- so the star we hold has no position to map against. They
      // are single-star nodes, so centring is exact rather than a guess.
      var dots2 = k.stars.map(function (s) {
        return '<b class="dot' + (s.taken ? " on" : "") + (s.skill ? " sk" : "") +
               '" style="left:50%;top:50%"></b>';
      }).join("");
      img = '<span class="plot" style="width:' + art.w + "px;height:" + art.h + 'px">' +
            '<img src="' + art.uri + '" alt="">' + dots2 + "</span>";
    } else {
      img = '<span class="ignone"></span>';
    }
    // the skill a constellation pays out at the end. The game shows the icon of
    // the class skill the proc is bound to, not the constellation's own art --
    // Shepherd's Call reads as Curse of Frailty.
    var gr = k.stars.filter(function (s) { return s.skill && s.name; })
      .map(function (s) {
        var b = s.bound || {};
        var tex = String(b.tex || s.tex || "").toLowerCase().replace(/^ui\//, "");
        var gi = (treeico[tex] || {}).uri || "";
        return "<em>" + (gi ? '<img src="' + gi + '" alt="">' : "") +
               "<u>" + e(s.name) + "</u>" +
               (b.name ? "<s>" + e(b.name) + "</s>" : "") + "</em>";
      }).join("");
    var cid = key("k", k.name);
    TIPS[cid] = tipConst(k);
    // name first, then the artwork, then the star count, and finally the
    // granted skill on a line of its own -- which is the only way it gets
    // enough width to put its icon and both text lines on one baseline
    return '<div class="cst' + (k.complete ? " done" : "") + '" data-tip="' + cid + '">' +
           '<b class="cname">' + e(k.name) + '</b><div class="cart">' + img + "</div>" +
           "<span><i>" + k.starsTaken + " / " + k.starsTotal + " stars</i>" + gr +
           "</span></div>";
  }

  // one row, however many constellations there are -- up to a point, beyond
  // which they would be too narrow to read and are better left to wrap
  var nc = devo.constellations.length;
  var ccols = nc <= 14 ? "grid-template-columns:repeat(" + nc + ",minmax(0,1fr))"
                       : "grid-template-columns:repeat(auto-fill,minmax(240px,1fr))";
  var devoicons = affinityBar() + '<div class="cstg" style="' + ccols + '">' +
                  devo.constellations.map(constRow).join("") + "</div>";
  var devoPoints = devo.constellations.reduce(function (s, k) { return s + k.starsTaken; }, 0);

  // ----------------------------------------------------------------- legend
  var LEGEND = [
    ['<span class="lg-cell sm full"><span class="lg-box"></span></span>',
     "Maximum stats item",
     "Each rolled stat is at its maximum value. Each fixed stat is also at " +
     "maximum value possible."],
    ['<span class="lg-cell sm"><span class="lg-box"></span></span>',
     "3/5 at max",
     "How many of an item's rollable stats reached their ceiling, shown only " +
     "where it fell short of all of them."]
  ];
  var legendbar = '<div class="note">' +
    '<p class="lead"><span class="h3">Reading this sheet</span> ' +
    "<b>These are actual rolls, not ranges.</b> Every figure is what this " +
    "character's items really rolled, computed from the save and the game " +
    "database rather than read off the character record. Hover over items, " +
    "stats, skills and devotions for more detail.</p>" +
    '<div class="legend">' +
    LEGEND.map(function (l) {
      return '<div class="lgi"><span class="lgk">' + l[0] + '</span><span class="lgv"><b>' +
             e(l[1]) + "</b> — " + e(l[2]) + "</span></div>";
    }).join("") +
    // inside the list, with only the mark, so it starts where the other notes
    // start rather than where their boxes do
    '<div class="lgi ndps"><span class="lgk"><span class="ast">*</span></span>' +
    '<span class="lgv"><b>DPS</b> — the figure the game prints only covers ' +
    "weapon attacks against single targets. It does not include damage from " +
    "skills, procs, damage over time and every conditional bonus. It is " +
    "therefore misleading so not included.</span></div>" +
    "</div></div>";

  // ------------------------------------------------------------------ page
  var doll = '<div class="doll">' +
    '<div class="dcol">' + cell("Weapon") + cell("Head") + cell("Chest") + cell("Legs") +
      cell("Shoulders") + cell("Hands") + cell("Feet") + "</div>" +
    '<div class="dmid"><div class="cstack">' + attrbox +
      '<div class="cmid">' + combatbox + buffbox + "</div>" + resbox + "</div>" +
      '<div class="mstack">' + skilltrees + "</div></div>" +
    '<div class="dcol">' + cell("Off-hand") + cell("Belt") + cell("Ring", 0) +
      cell("Ring", 1) + cell("Amulet") + cell("Medal") + cell("Relic") + "</div>" +
    '<figure class="dart" id="portrait" title="Drag and drop a screenshot here">' +
      (D.portrait || "") +
      '<div class="pstack"></div>' +
      '<div class="pdrop"><span>Character screenshot</span>' +
      "<i>Drag and drop here, or click to choose.<br>Must be PNG or JPEG.<br>" +
      "<em>Drop several to turn the character.</em></i></div>" +
      '<div class="pcap"></div>' +
      '<button class="pclear" type="button" title="Remove this picture">&times;</button>' +
      '<div class="pbar">' +
      '<button class="pb" data-act="play" type="button" title="Rotate">&#9654;</button>' +
      '<button class="pb" data-act="step" type="button" ' +
        "title=\"Turn one step, as the game's arrow does\">&#8635;</button>" +
      '<button class="pb" data-act="stop" type="button" title="Stop">&#9646;&#9646;</button>' +
      "</div>" +
      '<input type="file" accept="image/png,image/jpeg,image/webp" multiple hidden>' +
    "</figure></div>";

  var page = '<div class="wrap">' +
    '<div class="topline"><span>Difficulty: <b>' + e(c.difficulty) + "</b></span>" +
    "<span>" + e(c.expansion || "") + (c.version ? " · " : "") +
      "<i>" + e(c.version || "") + "</i></span></div>" +
    "<h1>" + e(c.name) + '</h1><div class="sub">Level ' + c.level + " · " +
      e(c.masteries.join(" / ")) + "</div>" +
    doll +
    '<div class="panels"><section class="wide"><h2>Devotions · ' + nc +
      " constellations · " + devoPoints + " points</h2>" + devoicons + "</section></div>" +
    legendbar +
    '<div id="tip" role="tooltip"></div>' +
    "</div>";

  document.body.insertAdjacentHTML("afterbegin", page);
  window.TIPS = TIPS;
  window.CHARKEY = c.name;
  if (window.gdSheetReady) window.gdSheetReady();
})();
