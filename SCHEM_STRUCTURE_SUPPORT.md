# `.schem` Support in the Vanilla Structure System

This document explains how Minecraft loads `.nbt` structure files, and how DimDoors hooks into that
system so that WorldEdit/Sponge `.schem` files can be used anywhere a vanilla structure template can
(jigsaw pools, structure pieces, structure block loading, `/place template`, etc.).

## How vanilla loads `.nbt` structures

All structure templates go through **`StructureTemplateManager`**
(`net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager`).

Its constructor builds an ordered list of **`StructureTemplateManager.Source`** entries. A `Source`
is just a pair of functions:

| Function | Signature | Purpose |
|---|---|---|
| loader | `ResourceLocation -> Optional<StructureTemplate>` | try to load the template with this id |
| lister | `() -> Stream<ResourceLocation>` | enumerate every id this source can provide |

In 1.20.1 the constructor registers three sources, in order:

1. `loadFromGenerated` — `.nbt` files in the world save's `generated/` folder (structure blocks),
2. `loadFromTestStructures` — gametest structures (only when running in an IDE),
3. `loadFromResource` — `.nbt` files from datapacks/mod resources, found via a
   `FileToIdConverter("structures", ".nbt")`, i.e. `data/<namespace>/structures/**/*.nbt`.

When something calls `StructureTemplateManager#get(id)`, the manager walks the sources in order and
returns the first non-empty result, caching it. Each `.nbt` loader ends up in
`readStructure(InputStream)`: it reads gzip-compressed NBT with `NbtIo.readCompressed`, runs the
DataFixer, and calls `StructureTemplate#load(HolderGetter<Block>, CompoundTag)` to fill the
template's three key fields:

- `size` (`Vec3i`) — bounding box of the template,
- `palettes` (`List<Palette>`) — per-position `StructureBlockInfo(pos, state, nbt)` lists,
- `entityInfoList` (`List<StructureEntityInfo>`) — entity NBT plus position.

**`StructureTemplate` is the data structure everything else consumes** — jigsaw placement,
processors, `placeInWorld`, etc. never look at the file format again. So the cleanest way to support
a new format is to produce a `StructureTemplate` from it and register an extra `Source`.

## What DimDoors adds

### 1. `SchematicStructureTemplate` — Schematic → StructureTemplate bridge

`common/src/main/java/org/dimdev/dimdoors/api/util/SchematicStructureTemplate.java`

DimDoors already has a full Sponge-schematic loader used by the pocket system:
`org.dimdev.dimdoors.util.schematic.Schematic` (a codec over the Sponge v2 format) plus
`SchematicPlacer`/`RelativeBlockSample` (in the `common/src/main/schematics` source root).
`SchematicStructureTemplate` reuses that code to build a vanilla template object:

- `SchematicStructureTemplate(CompoundTag)` decodes the tag with `Schematic.fromNbt` (the same
  `Schematic.CODEC` the pocket loader uses), then delegates to
  `SchematicStructureTemplate(Schematic)`.
- `size` is set from the schematic's `Width`/`Height`/`Length`.
- The block palette is rebuilt into vanilla form: `SchematicPlacer.getBlockData` decodes the
  Sponge `BlockData` array (x + z·Width + y·Width·Length ordering), each index is mapped through the
  schematic's `BlockState` palette, and every position becomes a `StructureBlockInfo`. The infos are
  sorted with vanilla's own `addToLists`/`buildInfoList` helpers so the resulting single `Palette`
  behaves exactly like one loaded from `.nbt`.
- Block entities (Sponge stores them in a `BlockEntities` list with an int-array `Pos` and a
  capitalised `Id`) are attached to their `StructureBlockInfo` by position, with `Id` renamed to the
  vanilla lowercase `id` — the same fix `RelativeBlockSample.place` applies.
- Entities are converted to `StructureEntityInfo`s. Two fixes mirror what
  `RelativeBlockSample` does for the pocket pipeline:
  - `SchematicPlacer.fixEntityId` ensures a lowercase `id` key exists (Sponge writes `Id`;
    vanilla's `EntityType.create` reads `id` — without this, entities silently fail to spawn),
  - the schematic's `Offset` is subtracted from each entity position, since DimDoors schematics
    store entity positions relative to that offset.

Because the class *extends* `StructureTemplate` and only fills the superclass fields, everything
downstream (rotation, mirroring, structure processors, jigsaw assembly) works unchanged.

### 2. `StructureTemplateManagerMixin` — registering `.schem` as an accepted format

`common/src/main/java/org/dimdev/dimdoors/mixin/StructureTemplateManagerMixin.java`

The mixin injects into `StructureTemplateManager`'s constructor at the third
`ImmutableList.Builder#add` call (`ordinal = 2`, `shift = AFTER` — i.e. right after vanilla adds its
datapack `.nbt` source) and captures the local builder to append one more source:

```java
builder.add(new StructureTemplateManager.Source(this::loadSchemFromResource, this::listSchemResources));
```

- `listSchemResources` uses a `FileToIdConverter("structures", ".schem")`, so the source picks up
  `data/<namespace>/structures/**/*.schem` from any datapack or mod — the exact analogue of how
  vanilla finds `.nbt` files.
- `loadSchemFromResource` opens the resource, reads it with `NbtIo.readCompressed` (Sponge
  schematics are gzip-compressed NBT, same as `.nbt` files), and constructs a
  `SchematicStructureTemplate` from the tag.

Because the source is appended *after* vanilla's, `.nbt` always wins when both
`foo.nbt` and `foo.schem` exist for the same id; `.schem` acts as a fallback format.

The mixin is registered in `common/src/main/resources/dimdoors-common.mixins.json`, so it applies on
both Fabric and Forge.

### 3. Access widener entries

The mixin and template subclass need access to package-private/private vanilla members. These were
already present in `common/src/main/resources/dimdoors.accesswidener`:

```
accessible class  ...StructureTemplateManager$Source
accessible method ...StructureTemplateManager$Source <init> (Ljava/util/function/Function;Ljava/util/function/Supplier;)V
accessible class  ...StructureTemplateManager$InputStreamOpener
accessible field  ...StructureTemplate size Lnet/minecraft/core/Vec3i;
accessible field  ...StructureTemplate palettes Ljava/util/List;
accessible field  ...StructureTemplate entityInfoList Ljava/util/List;
accessible method ...StructureTemplate addToLists (...)V
accessible method ...StructureTemplate buildInfoList (...)Ljava/util/List;
accessible method ...StructureTemplate$Palette <init> (Ljava/util/List;)V
```

## History: the older attempt

Waterpicker's earlier implementation is still in the tree and is what this feature revives:

- `939d9d7b` — *"Datagen ... and .schems can be used with jigsaw now."* added the mixin, the
  template subclass, and the access widener entries, and it worked in-game.
- `d03bfaa0` — *"Disable StructureTemplateManagerMixin."* removed only the entry from
  `dimdoors-common.mixins.json` (plus a debug `println`), leaving the code dormant.

The revival re-adds the mixin registration and fixes the gaps in `SchematicStructureTemplate`
(entity `Id`/`id` mismatch, missing entity offset handling, block-entity `Id` rename) and deletes
two dead leftover methods (an empty `loadFromSchematic` stub and an unused copy of vanilla's
`loadPalette`).

## Using it

Drop a Sponge v2 `.schem` file under `data/<namespace>/structures/` in any datapack or mod jar and
reference it by id like any structure template. For example,
`data/dimdoors/structures/gateways/end_gateway.schem` (already shipped) is now loadable as
`dimdoors:gateways/end_gateway` from a jigsaw pool:

```java
StructurePoolElement.single("dimdoors:gateways/end_gateway", processorList)
```

or from a structure block / `/place template dimdoors:gateways/end_gateway`.

## Known limitations

- `SchematicPlacer.getBlockData` reads the Sponge `BlockData` array one byte per block, without
  varint decoding. Schematics whose palette has more than 127 entries will decode incorrectly. This
  is a pre-existing limitation shared with the entire DimDoors pocket pipeline, so any schematic
  that already works as a pocket will also work here.
- A `.schem` has a palette entry for every cell of its bounding box, so air inside the box is placed
  as air (vanilla `.nbt` templates behave the same way; use structure void in `.nbt` if you need
  pass-through — Sponge format has no equivalent).
- Only the Sponge *block/entity* data is used; the commented-out biome palette support in
  `Schematic` is ignored, matching the rest of the mod.
