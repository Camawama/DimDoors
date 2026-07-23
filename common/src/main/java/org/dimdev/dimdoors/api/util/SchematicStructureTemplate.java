package org.dimdev.dimdoors.api.util;

import com.google.common.collect.Lists;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.dimdev.dimdoors.util.schematic.Schematic;
import org.dimdev.dimdoors.util.schematic.SchematicPlacer;

import java.util.List;
import java.util.stream.Collectors;

public class SchematicStructureTemplate extends StructureTemplate {
    public SchematicStructureTemplate(CompoundTag tag) {
        this(Schematic.fromNbt(tag));
    }

    public SchematicStructureTemplate(Schematic schematic) {
        this.size = new Vec3i(schematic.getWidth(), schematic.getHeight(), schematic.getLength());

        this.setAuthor(schematic.getMetadata().author());

        this.palettes.clear();
        this.entityInfoList.clear();

        loadPalette(schematic);

        schematic.getEntities().forEach(compoundTag -> {
            SchematicPlacer.fixEntityId(compoundTag);

            var array = compoundTag.getList("Pos", Tag.TAG_DOUBLE);
            // Entity positions are stored relative to the schematic's offset, same as in RelativeBlockSample
            var vec3 = new Vec3(array.getDouble(0), array.getDouble(1), array.getDouble(2)).subtract(Vec3.atLowerCornerOf(schematic.getOffset()));
            var pos = BlockPos.containing(vec3);

            entityInfoList.add(new StructureEntityInfo(vec3, pos, compoundTag));
        });
    }

    private void loadPalette(Schematic schematic) {
        List<StructureBlockInfo> list = Lists.newArrayList();
        List<StructureBlockInfo> list2 = Lists.newArrayList();
        List<StructureBlockInfo> list3 = Lists.newArrayList();

        var blockEntities = schematic.getBlockEntities().stream().collect(Collectors.toMap(compoundTag -> {
            var array = compoundTag.getIntArray("Pos");
            return new BlockPos(array[0], array[1], array[2]);
        }, SchematicStructureTemplate::fixBlockEntityId));

        var blockData = SchematicPlacer.getBlockData(schematic);
        var palleteList = schematic.getBlockPalette().inverse();
        for (int x = 0; x < schematic.getWidth(); x++) {
            for (int y = 0; y < schematic.getHeight(); y++) {
                for (int z = 0; z < schematic.getLength(); z++) {
                    var pos = new BlockPos(x, y, z);

                    var blockEntity = blockEntities.getOrDefault(pos, null);
                    var info = new StructureBlockInfo(pos, palleteList.get(blockData[x][y][z]), blockEntity);

                    addToLists(info, list, list2, list3);
                }
            }
        }

        List<StructureBlockInfo> list4 = buildInfoList(list, list2, list3);
        this.palettes.add(new Palette(list4));
    }

    private static CompoundTag fixBlockEntityId(CompoundTag nbt) {
        if (nbt.contains("Id")) {
            nbt.put("id", nbt.get("Id")); // boogers
            nbt.remove("Id");
        }

        return nbt;
    }
}
