# Wifoz: a tabby cat detective. Every level is a mystery: someone in the house did something, and the clues
# Wifoz sniffs out narrow the suspects down to one. Later levels have more suspects, clues that only rule things
# out, red herrings and fewer hearts. Input: $g (the saved game or null), $act, $arg, $now (Unix seconds).
# Output: {game: what to save, show: the widget's state}.

def rnd($s; $i): ((($s * 7919 + $i * 104729 + 13) % 2147483647) * 48271 % 2147483647) * 48271 % 2147483647;
def pick($list; $s; $i): $list[rnd($s; $i) % ($list | length)];
def shuffle($s): to_entries | sort_by(rnd($s; .key)) | map(.value);

def cast: [
  {id: "pip", name: "Pip", kind: "mouse"}, {id: "bruno", name: "Bruno", kind: "dog"}, {id: "kiki", name: "Kiki", kind: "parrot"},
  {id: "nib", name: "Nib", kind: "hamster"}, {id: "hoot", name: "Hoot", kind: "owl"}, {id: "olive", name: "Olive", kind: "tortoise"},
  {id: "dash", name: "Dash", kind: "rabbit"}, {id: "mo", name: "Mo", kind: "ferret"} ];
def colours: ["grey", "brown", "white", "ginger"];
def rooms: ["kitchen", "garden", "attic", "cellar"];
def times: ["at night", "by day"];
def mysteries: [
  ["The missing sock", "One of Grandma's red socks has vanished from the laundry basket."],
  ["The cake crime", "Someone took a bite out of the birthday cake, and it wasn't Wifoz."],
  ["The broken vase", "The blue vase in the hall lies in pieces on the floor."],
  ["The midnight piano", "Someone played three notes on the piano when the house was asleep."],
  ["The stolen yarn", "Wifoz's favourite ball of yarn is gone. This time it's personal."],
  ["The open fridge", "The fridge was left open and the cheese is missing."],
  ["The muddy sofa", "Little muddy prints run all the way across the white sofa."],
  ["The vanished key", "The key to the garden shed isn't on its hook."],
  ["The toppled plant", "The fern is on its side and the soil is everywhere."],
  ["The chewed slipper", "Dad's left slipper has teeth marks all over it."],
  ["The ringing bell", "The bell by the door rang, but nobody was there."],
  ["The empty treat jar", "The treat jar is empty. Every last treat, gone." ] ];
def herrings: ["A cold draught comes from under the door.", "Someone sneezed in the hallway. Everyone sneezes.",
  "The clock in the hall stopped at ten past three.", "There's a faint smell of mint near the stairs.",
  "A feather lies by the window, but it's an old one.", "The radio was left on, very quietly."];
def ranks: ["Kitten", "Sniffer", "Snoop", "Sleuth", "Inspector", "Detective", "Chief detective", "Mastermind"];
def maxEnergy: 5;

def describe($s): "\($s.colour) · \($s.room) · \($s.time)";

def clueText($c; $sus): if $c.kind == "is" then
    (if $c.attr == "colour" then "Wifoz found \($c.value) hairs at the scene."
     elif $c.attr == "room" then "Dusty pawprints lead back to the \($c.value)."
     else "It happened \($c.value)." end)
  elif $c.kind == "not" then
    (if $c.attr == "colour" then "Not a single \($c.value) hair anywhere near it."
     elif $c.attr == "room" then "Nobody came from the \($c.value): the door was shut all day."
     else "It didn't happen \($c.value)." end)
  elif $c.kind == "alibi" then ($sus[] | select(.id == $c.who)) as $p | "\($p.name) the \($p.kind) was asleep in a basket the whole time."
  else $c.text end;

def holds($c; $s): if $c.kind == "is" then $s[$c.attr] == $c.value elif $c.kind == "not" then $s[$c.attr] != $c.value
  elif $c.kind == "alibi" then $s.id != $c.who else true end;

# A case for this level: suspects with attributes, a culprit, and clues that leave exactly one suspect.
def newCase($level; $seed):
  ([3 + (($level - 1) / 2 | floor), 8] | min) as $k
  | (cast | shuffle($seed) | .[:$k] | to_entries | map(.value + {
      colour: pick(colours; $seed; .key * 3 + 1), room: pick(rooms; $seed; .key * 3 + 2), time: pick(times; $seed; .key * 3 + 3)})) as $sus
  | ($sus[rnd($seed; 99) % $k]) as $cul
  | ([ (if $level < 4 then ["colour", "room", "time"] | map({kind: "is", attr: ., value: $cul[.]}) else [] end),
       (["colour", "room", "time"] | map(. as $a | (if $a == "colour" then colours elif $a == "room" then rooms else times end)
         | map(select(. != $cul[$a]) | {kind: "not", attr: $a, value: .})) | add),
       ($sus | map(select(.id != $cul.id) | {kind: "alibi", who: .id})) ] | add | shuffle($seed + 7)) as $pool
  # Greedy: take clues that rule someone out, alibis last at higher levels, until one suspect is left.
  | ($pool | sort_by(if .kind == "alibi" and $level >= 5 then 1 else 0 end)) as $ordered
  | (reduce $ordered[] as $c ({left: $sus, clues: []};
      if (.left | length) <= 1 then . else
        (.left | map(select(holds($c; .)))) as $after
        | if ($after | length) < (.left | length) then {left: $after, clues: (.clues + [$c])} else . end end)) as $r
  | ([if $level >= 3 then 1 else 0 end, if $level >= 6 then 1 else 0 end, if $level >= 9 then 1 else 0 end] | add) as $nh
  | ([range($nh)] | map({kind: "herring", text: pick(herrings; $seed; . + 50)})) as $hs
  | ($r.clues + $hs | to_entries | sort_by(if .value.kind == "herring" then rnd($seed; .key) % (($r.clues | length) + 1) + 0.5 else .key end) | map(.value)) as $clues
  | (pick(mysteries; $seed; $level)) as $m
  | {level: $level, seed: $seed, title: $m[0], story: $m[1], suspects: $sus, culprit: $cul.id,
     clues: ($clues | map(. + {text: clueText(.; $sus)})), found: 0, hearts: (if $level >= 5 then 2 else 3 end), cleared: []};

def fresh($now): {case: newCase(1; $now % 100000), energy: maxEnergy, energyAt: $now, fedAt: 0, pettedAt: 0, solved: 0, news: "A new case! Tap Sniff to look for clues."};

# Energy (paws) comes back one every 15 minutes, up to five.
def regen($now): (((($now - .energyAt) / 900) | floor)) as $n
  | if .energy >= maxEnergy then .energyAt = $now
    elif $n > 0 then .energy = ([.energy + $n, maxEnergy] | min) | .energyAt = (.energyAt + $n * 900) else . end;

def act($act; $arg; $now):
  if $act == "sniff" then
    if .case.found >= (.case.clues | length) then .news = "No more clues: time to name the culprit."
    elif .energy < 1 then .news = "Wifoz is too tired to sniff. Feed him, or wait a bit."
    else .energy -= 1 | .case.found += 1 | .news = "Clue \(.case.found): \(.case.clues[.case.found - 1].text)" end
  elif $act == "feed" then
    if $now - .fedAt < 1800 then .news = "Wifoz isn't hungry yet. He licks his paw instead."
    else .fedAt = $now | .energy = ([.energy + 2, maxEnergy] | min) | .news = "Crunch crunch. Wifoz feels sharp again (+2 paws)." end
  elif $act == "pet" then
    if $now - .pettedAt < 1800 then .news = "Purrr."
    else .pettedAt = $now | .energy = ([.energy + 1, maxEnergy] | min) | .news = "Purrr. A good scratch is worth a paw (+1)." end
  elif $act == "accuse" then
    (.case.suspects[] | select(.id == $arg)) as $who
    | if $who == null or (.case.cleared | index($arg)) != null then .
      elif $arg == .case.culprit then
        .solved += 1 | .news = "Solved! It was \($who.name) the \($who.kind). Case \(.case.level) closed; a harder one awaits."
        | .case = newCase(.case.level + 1; ($now + .solved * 7919) % 100000)
      else
        .case.hearts -= 1 | .case.cleared += [$arg]
        | if .case.hearts <= 0 then .news = "Wrong again: the trail went cold. A new mystery on the same level."
            | .case = newCase(.case.level; ($now + 31) % 100000)
          else .news = "Not \($who.name): Wifoz shakes his head. \(.case.hearts) \(if .case.hearts == 1 then "heart" else "hearts" end) left." end
      end
  elif $act == "new" then fresh($now)
  else . end;

def show: .case as $c | .energy as $e | {
  level: $c.level, title: $c.title, story: $c.story, news: .news,
  rank: ranks[[($c.level - 1), (ranks | length) - 1] | min], hat: ($c.level >= 3), solved: .solved,
  energy: .energy, paws: ([range(maxEnergy)] | map(if . < $e then "●" else "○" end) | join("")),
  hearts: ([range(if $c.level >= 5 then 2 else 3 end)] | map(if . < $c.hearts then "♥" else "♡" end) | join("")),
  found: $c.found, total: ($c.clues | length), canSniff: ($c.found < ($c.clues | length)),
  clues: ($c.clues[:$c.found] | map(.text)),
  suspects: ($c.suspects | map(.id as $i | {id, name, kind, about: describe(.), out: (($c.cleared | index($i)) != null)})),
  hungry: false, line: "Case \($c.level) · \($c.found)/\($c.clues | length) clues · \(.energy) paws" };

($g // fresh($now)) | regen($now) | act($act; $arg; $now) | {game: ., show: show}
