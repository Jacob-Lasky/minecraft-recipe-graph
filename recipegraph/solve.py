"""Resolve a target item into a crafting tree, pruned against what you already have.

The three things that make this non-trivial, and how each is handled:

1. CYCLES. Recipe graphs are not DAGs (ingot -> block -> ingot). Guarded by an
   explicit ancestor set per path; a repeat is emitted as a `cycle` leaf rather
   than recursed into. DO NOT replace this with a global visited set -- that would
   wrongly prune legitimate diamond-shaped reuse of the same intermediate.

2. RECIPE CHOICE. Items have many recipes and the best one depends on inventory,
   so choice belongs here, not in the extractors. Scored by how much of the recipe
   is already satisfied, then by simplicity. Overridable per item.

3. INVENTORY IS CONSUMED, NOT JUST CHECKED. `have` is drawn down as the tree is
   built, so two sibling branches cannot both claim the same 5 redstone. This is
   why the walk is single-pass and ordered rather than a pure function per node.
"""

import collections

from .model import split_key

STATUS_HAVE = "have"        # fully covered by inventory
STATUS_PARTIAL = "partial"  # some from inventory, remainder crafted
STATUS_CRAFT = "craft"      # crafted from sub-ingredients
STATUS_RAW = "raw"          # no recipe known and not in inventory -> shopping list
STATUS_CYCLE = "cycle"      # recipe loops back on an ancestor
STATUS_DEPTH = "depth"      # hit the depth/size cap


def _count_cycles(node):
    """How many cycle leaves this subtree bottomed out on."""
    n = 1 if node.get("status") == STATUS_CYCLE else 0
    return n + sum(_count_cycles(c) for c in node.get("children") or ())


def _count_nodes(node):
    return 1 + sum(_count_nodes(c) for c in node.get("children") or ())


class Solver:
    def __init__(self, graph, have=None, raw=None, overrides=None,
                 max_depth=24, max_nodes=4000, craftables=None, branch_tries=4,
                 work_budget=None):
        self.g = graph
        self.pool = collections.Counter(have or {})
        self.raw = set(raw or ())            # user-declared "stop here" items
        self.craftables = set(craftables or ())  # AE2 autocraftable -> treat as have
        self.overrides = dict(overrides or {})
        self.max_depth = max_depth
        self.max_nodes = max_nodes
        self.branch_tries = branch_tries
        self.nodes = 0
        # Monotonic work counter. `nodes` is REWOUND when a backtrack discards a subtree,
        # so discarded work never counts toward max_nodes -- with branch_tries retries at
        # every level that makes the search effectively unbounded, and on a 340k-recipe
        # graph it simply never returns. This counter is never rewound, so it is the only
        # real termination guarantee. DO NOT add it to a snapshot/restore.
        self.work = 0
        self.work_budget = work_budget or max(50000, max_nodes * 20)
        self.exhausted = False
        self.leaf_totals = collections.Counter()
        self.used_from_stock = collections.Counter()

    # ---- inventory ------------------------------------------------------

    def available(self, key):
        """Stock for a key, counting a wildcard-meta variant as interchangeable."""
        n = self.pool.get(key, 0)
        base, meta = split_key(key)
        if meta == "*":
            for k, v in self.pool.items():
                kb, _ = split_key(k)
                if kb == base:
                    n += v
        return n

    def take(self, key, want):
        got = min(want, self.available(key))
        if got <= 0:
            return 0
        remaining = got
        # drain the exact key first, then wildcard-equivalent metas
        for k in [key] + [k for k in list(self.pool) if k != key and split_key(k)[0] == split_key(key)[0]]:
            if remaining <= 0:
                break
            if split_key(key)[1] != "*" and k != key:
                continue
            avail = self.pool.get(k, 0)
            if avail <= 0:
                continue
            used = min(avail, remaining)
            self.pool[k] -= used
            self.used_from_stock[k] += used
            remaining -= used
        return got - remaining

    # ---- choice ---------------------------------------------------------

    def pick_alternative(self, ingredient):
        """Which of an input slot's alternatives to actually use."""
        alts = ingredient.alternatives
        if len(alts) == 1:
            return alts[0]
        best, best_score = alts[0], -1.0
        for a in alts:
            score = 0.0
            if a.startswith("ore:"):
                members = self.g.ore_members.get(a[4:], [])
                score += max((self.available(m) for m in members), default=0) / 1e6
            else:
                score += min(self.available(a), 1e6) / 1e6
                if a in self.craftables:
                    score += 0.5
                if self.g.producers(a):
                    score += 0.25
            if score > best_score:
                best, best_score = a, score
        return best

    def score_recipe(self, recipe, ancestors=frozenset()):
        """Higher is better: prefer recipes we can mostly satisfy from stock.

        Recipes that feed back into an ancestor are ranked LAST. Without this,
        `ingot -> block -> 9 ingots` scores well (one simple input) and gets picked
        over a real production route, producing a plan that asks for the very thing
        being crafted. The cycle guard still catches it; this stops us choosing it.
        """
        satisfied = 0
        cyclic = 0
        for ing in recipe.inputs:
            alt = self.pick_alternative(ing)
            if self.available(alt) >= ing.qty or alt in self.craftables:
                satisfied += 1
            if alt in ancestors and self.available(alt) < ing.qty:
                cyclic += 1
        # simplicity tiebreak: fewer inputs, and prefer plain crafting over machines
        simple = 1.0 / (1 + len(recipe.inputs))
        plain = 0.1 if recipe.category.startswith("crafting") else 0.0
        # A container fill/empty never counts as production, so it loses to any real
        # recipe regardless of how well stocked it looks.
        return (0 if recipe.transfer else 1, -cyclic, satisfied, simple + plain)

    def pick_recipe(self, key, ancestors=frozenset()):
        override = self.overrides.get(key)
        candidates = self.g.producers(key)
        if not candidates:
            return None
        if override:
            for r in candidates:
                if r.rid == override:
                    return r
        return max(candidates, key=lambda r: self.score_recipe(r, ancestors))

    # ---- expansion ------------------------------------------------------

    def resolve_ore(self, key, need, ancestors, depth):
        """An `ore:` node resolves to whichever concrete member suits us best."""
        members = self.g.ore_members.get(key[4:], [])
        if not members:
            self.leaf_totals[key] += need
            return {"key": key, "name": self.g.display(key), "need": need,
                    "status": STATUS_RAW, "note": "oredict members unknown"}
        best = max(members, key=lambda m: (self.available(m), m in self.craftables))
        child = self.expand(best, need, ancestors, depth)
        return {"key": key, "name": self.g.display(key), "need": need,
                "status": "oredict", "resolved_to": best, "children": [child]}

    def expand(self, key, need, ancestors=frozenset(), depth=0):
        self.nodes += 1
        self.work += 1
        node = {"key": key, "name": self.g.display(key), "need": need}

        if self.work > self.work_budget:
            self.exhausted = True
            node["status"] = STATUS_DEPTH
            self.leaf_totals[key] += need
            return node

        if self.nodes > self.max_nodes or depth > self.max_depth:
            node["status"] = STATUS_DEPTH
            self.leaf_totals[key] += need
            return node

        if key.startswith("ore:"):
            return self.resolve_ore(key, need, ancestors, depth)

        from_stock = self.take(key, need)
        if from_stock:
            node["from_stock"] = from_stock
        remainder = need - from_stock
        if remainder <= 0:
            node["status"] = STATUS_HAVE
            return node

        if key in self.raw or key in self.craftables:
            node["status"] = STATUS_HAVE if key in self.craftables else STATUS_RAW
            if key in self.craftables:
                node["note"] = "AE2 can autocraft"
            else:
                self.leaf_totals[key] += remainder
            return node

        if key in ancestors:
            node["status"] = STATUS_CYCLE
            self.leaf_totals[key] += remainder
            return node

        candidates = self.g.producers(key)
        if not candidates:
            node["status"] = STATUS_RAW
            self.leaf_totals[key] += remainder
            return node

        nxt = ancestors | {key}
        override = self.overrides.get(key)
        ranked = sorted(candidates, key=lambda r: self.score_recipe(r, nxt), reverse=True)
        if override:
            ranked.sort(key=lambda r: r.rid != override)

        # Try recipes best-first and BACKTRACK out of any whose subtree loops back
        # on an ancestor. Uncrafting recipes (block -> 9 ingots) otherwise get
        # chosen for their single simple input and produce a plan that asks for the
        # item being crafted. Only accept a cycling recipe if every option cycles.
        best = None  # (rank_tuple, attempt, state_to_restore)
        for rank, recipe in enumerate(ranked[: self.branch_tries]):
            if self.work > self.work_budget:
                break
            snapshot = (
                self.pool.copy(), self.used_from_stock.copy(),
                self.leaf_totals.copy(), self.nodes,
            )
            attempt = self._build(node, recipe, key, remainder, from_stock, nxt, depth)
            cycles = _count_cycles(attempt)
            if not cycles:
                return attempt
            # All routes may cycle -- in this dataset smelting recipes are absent, so
            # ore -> ingot chains dead-end and every route eventually loops. Keep the
            # most informative attempt: fewest cycle leaves, then the largest
            # expansion, so the plan still shows the real ingredients it did resolve.
            score = (cycles, -_count_nodes(attempt), rank)
            if best is None or score < best[0]:
                best = (score, attempt, (
                    self.pool.copy(), self.used_from_stock.copy(),
                    self.leaf_totals.copy(), self.nodes,
                ))
            self.pool, self.used_from_stock, self.leaf_totals, self.nodes = snapshot

        if best is None:
            node["status"] = STATUS_RAW
            self.leaf_totals[key] += remainder
            return node
        _score, attempt, restore = best
        self.pool, self.used_from_stock, self.leaf_totals, self.nodes = restore
        return attempt

    def _build(self, base, recipe, key, remainder, from_stock, ancestors, depth):
        """Expand one specific recipe choice for `key`."""
        per_run = next((q for k, q in recipe.outputs if k == key), 1) or 1
        runs = -(-remainder // per_run)  # ceil
        node = dict(base)
        node.update({
            "status": STATUS_PARTIAL if from_stock else STATUS_CRAFT,
            "recipe": recipe.rid,
            "category": recipe.category,
            "runs": runs,
            "per_run": per_run,
            "alternatives": len(self.g.producers(key)),
        })
        if recipe.machine:
            node["machine"] = recipe.machine

        children = []
        for ing in recipe.inputs:
            alt = self.pick_alternative(ing)
            child = self.expand(alt, ing.qty * runs, ancestors, depth + 1)
            if len(ing.alternatives) > 1:
                child["alt_count"] = len(ing.alternatives)
            children.append(child)
        node["children"] = children
        return node

    def solve(self, key, qty=1):
        tree = self.expand(key, qty)
        return {
            "target": key,
            "target_name": self.g.display(key),
            "qty": qty,
            "tree": tree,
            "shopping_list": [
                {"key": k, "name": self.g.display(k), "qty": n}
                for k, n in self.leaf_totals.most_common()
            ],
            "used_from_stock": [
                {"key": k, "name": self.g.display(k), "qty": n}
                for k, n in self.used_from_stock.most_common()
            ],
            "nodes": self.nodes,
            "work": self.work,
            "truncated": self.nodes > self.max_nodes or self.exhausted,
            "exhausted": self.exhausted,
        }
