package com.snake.evolutionarena

import android.content.Context
import org.json.JSONObject

data class ArenaMap(
    val id: String,
    val name: String,
    val kicker: String,
    val description: String,
    val mechanic: String,
    val accent: String,
    val accent2: String,
    val preview: String,
)

data class GameMode(
    val id: String,
    val name: String,
    val shortName: String,
    val description: String,
    val objective: String,
    val soundtrack: String,
    val bpm: Int,
)

data class Archetype(
    val id: String,
    val name: String,
    val title: String,
    val description: String,
    val perk: String,
    val accent: String,
)

data class Skin(
    val id: String,
    val name: String,
    val theme: String,
)

data class GameCatalog(
    val maps: List<ArenaMap>,
    val modes: List<GameMode>,
    val archetypes: List<Archetype>,
    val skins: List<Skin>,
) {
    companion object {
        fun load(context: Context): GameCatalog {
            val mapsJson = context.readAssetJson("game/maps.json")
            val modesJson = context.readAssetJson("game/modes.json")
            val archetypesJson = context.readAssetJson("game/archetypes.json")
            val skinsJson = context.readAssetJson("game/skins.json")

            val mapsArray = mapsJson.getJSONArray("maps")
            val maps = buildList {
                for (index in 0 until mapsArray.length()) {
                    val item = mapsArray.getJSONObject(index)
                    add(
                        ArenaMap(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            kicker = item.getString("kicker"),
                            description = item.getString("description"),
                            mechanic = item.getString("mechanic"),
                            accent = item.getString("accent"),
                            accent2 = item.getString("accent2"),
                            preview = item.getString("preview"),
                        ),
                    )
                }
            }

            val modesArray = modesJson.getJSONArray("modes")
            val modes = buildList {
                for (index in 0 until modesArray.length()) {
                    val item = modesArray.getJSONObject(index)
                    add(
                        GameMode(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            shortName = item.getString("shortName"),
                            description = item.getString("description"),
                            objective = item.getString("objective"),
                            soundtrack = item.getString("soundtrack"),
                            bpm = item.getInt("bpm"),
                        ),
                    )
                }
            }

            val archetypesArray = archetypesJson.getJSONArray("archetypes")
            val archetypes = buildList {
                for (index in 0 until archetypesArray.length()) {
                    val item = archetypesArray.getJSONObject(index)
                    add(
                        Archetype(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            title = item.getString("title"),
                            description = item.getString("description"),
                            perk = item.getString("perk"),
                            accent = item.getString("accent"),
                        ),
                    )
                }
            }

            val skinsArray = skinsJson.getJSONArray("skins")
            val skins = buildList {
                for (index in 0 until skinsArray.length()) {
                    val item = skinsArray.getJSONObject(index)
                    add(Skin(item.getString("id"), item.getString("name"), item.getString("theme")))
                }
            }

            return GameCatalog(maps, modes, archetypes, skins)
        }
    }
}

private fun Context.readAssetJson(path: String): JSONObject =
    assets.open(path).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
