import { access, readFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";

const root = process.cwd();
const assetRoot = path.join(root, "app", "src", "main", "assets");

async function load(name) {
  const value = JSON.parse(await readFile(path.join(assetRoot, "game", name), "utf8"));
  if (value.version !== 1) throw new Error(`${name}: unsupported schema version`);
  return value;
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function assertUnique(items, label) {
  const ids = new Set();
  for (const item of items) {
    assert(typeof item.id === "string" && item.id.length > 0, `${label}: empty id`);
    assert(!ids.has(item.id), `${label}: duplicate id ${item.id}`);
    ids.add(item.id);
  }
  return ids;
}

const [{ maps }, { modes }, { archetypes }, { skins }, { upgrades }] = await Promise.all([
  load("maps.json"),
  load("modes.json"),
  load("archetypes.json"),
  load("skins.json"),
  load("upgrades.json"),
]);

assert(maps.length === 3, "expected exactly three maps");
assert(modes.length === 3, "expected exactly three modes");
assert(archetypes.length === 4, "expected exactly four archetypes");
assert(skins.length === 12, "expected exactly twelve skins");

const mapIds = assertUnique(maps, "maps");
assertUnique(modes, "modes");
assertUnique(archetypes, "archetypes");
assertUnique(skins, "skins");
assertUnique(upgrades, "upgrades");

await Promise.all(maps.map((map) => access(path.join(assetRoot, map.preview))));

for (const upgrade of upgrades) {
  assert(["passive", "active", "relic", "map"].includes(upgrade.kind), `invalid upgrade kind: ${upgrade.id}`);
  assert(Number.isFinite(upgrade.amount), `invalid amount: ${upgrade.id}`);
  assert(Number.isInteger(upgrade.maxRank) && upgrade.maxRank > 0, `invalid maxRank: ${upgrade.id}`);
  if (upgrade.kind === "map") assert(mapIds.has(upgrade.map), `unknown map on ${upgrade.id}`);
}

for (const theme of ["neon", "nature", "mutation", "mythic"]) {
  assert(skins.filter((skin) => skin.theme === theme).length === 3, `${theme}: expected three skins`);
}
for (const skin of skins) {
  for (const forbidden of ["damage", "speed", "pickup", "collision", "health"]) {
    assert(!(forbidden in skin), `${skin.id}: cosmetic contains gameplay field ${forbidden}`);
  }
  for (const visual of ["head", "body", "trail", "entrance", "elimination"]) {
    assert(typeof skin[visual] === "string" && skin[visual].length > 0, `${skin.id}: missing ${visual}`);
  }
}

console.log(`Validated ${maps.length} maps, ${modes.length} modes, ${archetypes.length} archetypes, ${upgrades.length} upgrades and ${skins.length} skins.`);
