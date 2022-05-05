package com.nukkitx.proxypass.network.bedrock.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nukkitx.nbt.NBTOutputStream;
import com.nukkitx.nbt.NbtMap;
import com.nukkitx.nbt.NbtType;
import com.nukkitx.nbt.NbtUtils;
import com.nukkitx.nbt.util.stream.LittleEndianDataOutputStream;
import com.nukkitx.protocol.bedrock.data.inventory.*;
import com.nukkitx.protocol.bedrock.packet.CraftingDataPacket;
import com.nukkitx.proxypass.ProxyPass;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Value;
import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

@Log4j2
@UtilityClass
public class RecipeUtils {
    private static final char[] SHAPE_CHARS = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J'};

    public static void writeRecipes(CraftingDataPacket packet, ProxyPass proxy) {
        Object object = proxy.loadGzipNBT("block_palette.nbt");
        List<NbtMap> paletteTags = null;
        if (object instanceof NbtMap) {
            NbtMap map = (NbtMap) object;
            paletteTags = map.getList("blocks", NbtType.COMPOUND);
        } else {
            log.warn("Failed to load block palette for recipes. Output will contain not contain block states!");
        }

        List<CraftingDataEntry> entries = new ArrayList<>();
        List<PotionMixDataEntry> potions = new ArrayList<>();
        List<ContainerMixDataEntry> containers = new ArrayList<>();

        for (CraftingData craftingData : packet.getCraftingData()) {
            CraftingDataEntry entry = new CraftingDataEntry();

            CraftingDataType type = craftingData.getType();
            entry.type = type.ordinal();

            if (type != CraftingDataType.MULTI) {
                entry.block = craftingData.getCraftingTag();
            } else {
                entry.uuid = craftingData.getUuid();
            }

            if (type == CraftingDataType.SHAPED || type == CraftingDataType.SHAPELESS || type == CraftingDataType.SHAPELESS_CHEMISTRY || type == CraftingDataType.SHULKER_BOX || type == CraftingDataType.SHAPED_CHEMISTRY) {
                entry.id = craftingData.getRecipeId();
                entry.priority = craftingData.getPriority();
                entry.output = writeItemArray(craftingData.getOutputs().toArray(new ItemData[0]), true, paletteTags);
            }
            if (type == CraftingDataType.SHAPED || type == CraftingDataType.SHAPED_CHEMISTRY) {

                int charCounter = 0;
                // ItemData[] inputs = craftingData.getInputs().toArray(new ItemData[0]);
                List<ItemData> inputs = craftingData.getInputs();
                Map<Item, Character> charItemMap = new HashMap<>();
                char[][] shape = new char[craftingData.getHeight()][craftingData.getWidth()];

                for (int height = 0; height < craftingData.getHeight(); height++) {
                    Arrays.fill(shape[height], ' ');
                    int index = height * craftingData.getWidth();
                    for (int width = 0; width < craftingData.getWidth(); width++) {
                        int slot = index + width;
                        Item item = itemFromNetwork(inputs.get(slot), false, paletteTags);

                        if (item == Item.EMPTY) {
                            continue;
                        }

                        Character shapeChar = charItemMap.get(item);
                        if (shapeChar == null) {
                            shapeChar = SHAPE_CHARS[charCounter++];
                            charItemMap.put(item, shapeChar);
                        }

                        shape[height][width] = shapeChar;
                    }
                }

                String[] shapeString = new String[shape.length];
                for (int i = 0; i < shape.length; i++) {
                    shapeString[i] = new String(shape[i]);
                }
                entry.shape = shapeString;

                Map<Character, Item> itemMap = new HashMap<>();
                for (Map.Entry<Item, Character> mapEntry : charItemMap.entrySet()) {
                    itemMap.put(mapEntry.getValue(), mapEntry.getKey());
                }
                entry.input = itemMap;
            }
            if (type == CraftingDataType.SHAPELESS || type == CraftingDataType.SHAPELESS_CHEMISTRY || type == CraftingDataType.SHULKER_BOX) {
                entry.input = writeItemArray(craftingData.getInputs().toArray(new ItemData[0]), false, paletteTags);
            }

            if (type == CraftingDataType.FURNACE || type == CraftingDataType.FURNACE_DATA) {
                Integer damage = craftingData.getInputDamage();
                if (damage == 0x7fff) damage = -1;
                if (damage == 0) damage = null;
                entry.input = new Item(craftingData.getInputId(), ProxyPass.legacyIdMap.get(craftingData.getInputId()), damage, null, null, null);
                entry.output = itemFromNetwork(craftingData.getOutputs().get(0), true, paletteTags);
            }
            entries.add(entry);
        }
        for (PotionMixData potion : packet.getPotionMixData()) {
            potions.add(new PotionMixDataEntry(
                    ProxyPass.legacyIdMap.get(potion.getInputId()),
                    potion.getInputMeta(),
                    ProxyPass.legacyIdMap.get(potion.getReagentId()),
                    potion.getReagentMeta(),
                    ProxyPass.legacyIdMap.get(potion.getOutputId()),
                    potion.getOutputMeta()
            ));
        }

        for (ContainerMixData container : packet.getContainerMixData()) {
            containers.add(new ContainerMixDataEntry(
                    ProxyPass.legacyIdMap.get(container.getInputId()),
                    ProxyPass.legacyIdMap.get(container.getReagentId()),
                    ProxyPass.legacyIdMap.get(container.getOutputId())
            ));
        }

        Recipes recipes = new Recipes(ProxyPass.CODEC.getProtocolVersion(), entries, potions, containers);

        proxy.saveJson("recipes.json", recipes);
    }

    private static List<Item> writeItemArray(ItemData[] inputs, boolean output, List<NbtMap> paletteTags) {
        List<Item> outputs = new ArrayList<>();
        for (ItemData input : inputs) {
            Item item = itemFromNetwork(input, output, paletteTags);
            if (item != Item.EMPTY) {
                outputs.add(item);
            }
        }
        return outputs;
    }

    private static String nbtToBase64(NbtMap tag) {
        if (tag != null) {
            ByteArrayOutputStream tagStream = new ByteArrayOutputStream();
            try (NBTOutputStream writer = NbtUtils.createWriterLE(tagStream)) {
                writer.writeTag(tag);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return Base64.getEncoder().encodeToString(tagStream.toByteArray());
        } else {
            return null;
        }
    }

    private static Item itemFromNetwork(ItemData data, boolean output, List<NbtMap> paletteTags) {
        int id = data.getId();
        String identifier = ProxyPass.legacyIdMap.get(id);
        Integer damage = (int) data.getDamage();
        Integer count = data.getCount();
        String tag = nbtToBase64(data.getTag());

        if (id == 0) {
            return Item.EMPTY;
        }
        if (damage == 0 || (damage == -1 && output)) damage = null;
        if (count == 1) count = null;

        String blockNBT = null;
        if (data.getBlockRuntimeId() != 0) {
            blockNBT = encodeNbtToString(paletteTags.get(data.getBlockRuntimeId()));
        }

        return new Item(id, identifier, damage, count, tag, blockNBT);
    }

    private static String encodeNbtToString(NbtMap tag) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (NBTOutputStream stream = new NBTOutputStream(new LittleEndianDataOutputStream(byteArrayOutputStream))) {
            stream.writeTag(tag);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray());
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class CraftingDataEntry {
        private String id;
        private int type;
        private Object input;
        private Object output;
        private String[] shape;
        private String block;
        private UUID uuid;
        private Integer priority;
    }

    @AllArgsConstructor
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class PotionMixDataEntry {
        private String inputId;
        private int inputMeta;
        private String reagentId;
        private int reagentMeta;
        private String outputId;
        private int outputMeta;
    }

    @AllArgsConstructor
    @Getter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class ContainerMixDataEntry {
        private String inputId;
        private String reagentId;
        private String outputId;
    }

    @Value
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private static class Item {
        public static final Item EMPTY = new Item(0, "minecraft:air", null, null, null, null);

        int legacyId;
        String id;
        Integer damage;
        Integer count;
        String nbt_b64;
        String block_state_b64;
    }

    @Value
    private static class Recipes {
        int version;
        List<CraftingDataEntry> recipes;
        List<PotionMixDataEntry> potionMixes;
        List<ContainerMixDataEntry> containerMixes;
    }
}
