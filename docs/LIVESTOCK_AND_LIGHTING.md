# Colony lighting and livestock

Citizens check artificial light between plans. Crops target light 9; colony walkways, interiors and mine passages target 7. Existing mining passages are checked near workers; newly dug passages are remembered in world save data. Preparation uses real workstations, fuel and inventory torches. Searches do not force-load chunks.

Shepherds prioritize husbandry. They create a fenced pen in a pasture district, prepare shallow natural terrain with real dirt, and choose a fence wood available locally. Steep, obstructed, flooded or unloaded sites are rejected. Herding escorts an adult through the gate and closes the gate after the worker leaves. Breeding feeds two eligible adults of the same species together; pending births reserve population slots.

The Forge common configuration provides:

- `livestock.maxPerSpecies = 10`: colony-wide count per farm species, including babies.
- `livestock.maxWolves = 50`: colony guard wolves across dimensions and unloaded chunks. Zero prevents new recruitment.

The ownership registry is persisted in overworld saved data. Deaths free slots. Ordinary hunting avoids colony-owned stock; dedicated herd management can slaughter excess adults but preserves babies and two adults. When prepared food is scarce, a full herd can supply one adult and breed a replacement without exceeding the cap. Sheep are sheared using actual shears, cows/goats supply milk into buckets, and eggs and slaughter drops are collected. Cooking is requested when a known furnace exists.

Wild wolves require real bones and probabilistic taming. Player pets and named animals are not recruited. Colony wolves escort citizens and target nearby hostile monsters, excluding creepers. The system does not spawn animals, bones or other resources.

Validation covers limit policy, simultaneous birth reservations, save/load counts, pen geometry, wood variants, lighting thresholds and the existing Java/Python suites. Live movement, enclosure containment, mating and combat still require a gameplay check after restarting the game/service; automated unit tests do not simulate a running world.
