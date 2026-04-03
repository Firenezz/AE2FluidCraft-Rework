package com.glodblock.github.network;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import com.glodblock.github.util.Util;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.apache.commons.lang3.tuple.ImmutablePair;

public class CPacketPickBlock implements IMessage {

    private ItemStack pickedItem;

    public CPacketPickBlock() {}

    public CPacketPickBlock(ItemStack pickedItem) {
        this.pickedItem = pickedItem;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pickedItem = ByteBufUtils.readItemStack(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeItemStack(buf, pickedItem == null ? null : pickedItem);
    }

    @SideOnly(Side.CLIENT)
    public static CPacketPickBlock createFromClientPick(EntityClientPlayerMP player) {
        if (player == null || player.capabilities.isCreativeMode) {
            return null;
        }

        // Basic distance limit (matches previous logic, keeps it bounded)
        final double distance = 5.0D;
        Vec3 eyePos = Vec3.createVectorHelper(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 lookDir = player.getLook(1.0F);
        Vec3 targetPos = eyePos.addVector(lookDir.xCoord * distance, lookDir.yCoord * distance, lookDir.zCoord * distance);

        MovingObjectPosition rayTrace = player.worldObj.rayTraceBlocks(eyePos, targetPos, true);
        if (rayTrace == null || rayTrace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }

        int x = rayTrace.blockX;
        int y = rayTrace.blockY;
        int z = rayTrace.blockZ;

        Block block = player.worldObj.getBlock(x, y, z);
        int metadata = player.worldObj.getBlockMetadata(x, y, z);

        ItemStack blockItem = block.getPickBlock(rayTrace, player.worldObj, x, y, z, player);
        if (blockItem == null) {
            blockItem = new ItemStack(Item.getItemFromBlock(block), 1, block.getDamageValue(player.worldObj, x,y,z));
        }

        if (blockItem.getItem() == null) {
            return null;
        }

        ItemStack copy = blockItem.copy();
        copy.stackSize = 1;

        return new CPacketPickBlock(copy);
    }

    public static class Handler implements IMessageHandler<CPacketPickBlock, IMessage> {

        @Override
        public IMessage onMessage(CPacketPickBlock message, MessageContext ctx) {
            EntityPlayer player = ctx.getServerHandler().playerEntity;
            if (player.capabilities.isCreativeMode) {
                return null;
            }
            Container container = player.openContainer;

            if (container != null) {
                if (container instanceof ContainerPlayer) {
                    ImmutablePair<Integer, ItemStack> result = Util.getUltraWirelessTerm(player);
                    if (result != null) {
                        final ItemStack wirelessTerm = result.getRight();

                        ItemStack itemToPick = message.pickedItem;
                        if (itemToPick != null && itemToPick.getItem() != null) {
                            ItemStack safe = itemToPick.copy();
                            safe.stackSize = 1;

                            IGridNode gridNode = Util.getWirelessGrid(wirelessTerm);
                            if (gridNode != null && Util.rangeCheck(wirelessTerm, player, gridNode)) {
                                pickItem(result.getRight(), safe, gridNode, player);
                            }
                        }
                    }
                }
            }
            return null;
        }

        private void pickItem(ItemStack terminal, ItemStack itemToPick, IGridNode gridNode, EntityPlayer player) {
            IGrid targetGrid = gridNode.getGrid();
            IMEMonitor<IAEItemStack> itemStorage = null;

            if (targetGrid != null) {
                IStorageGrid sg = targetGrid.getCache(IStorageGrid.class);
                if (sg != null) {
                    itemStorage = sg.getItemInventory();
                }
            }


            if (itemStorage != null) {
                int maxSize = itemToPick.getMaxStackSize();
                int slotToInsert = getSlotToInsert(itemToPick, player.inventory);

                ItemStack is = player.inventory.mainInventory[slotToInsert];

                if (is != null) {
                    if (itemToPick.isItemEqual(is)){
                        // Refill
                        int fillSize = maxSize - is.stackSize;
                        IAEItemStack ias = AEApi.instance().storage().createItemStack(itemToPick);
                        ias.setStackSize(fillSize);

                        IAEItemStack extractedItem = itemStorage
                            .extractItems(ias, Actionable.MODULATE, new PlayerSource(player, null));
                        if (extractedItem != null) {
                            player.inventory.addItemStackToInventory(extractedItem.getItemStack());
                        }
                    } else {
                        // Try to move away the item
                        IAEItemStack ias = AEApi.instance().storage().createItemStack(itemToPick);
                        ias.setStackSize(maxSize);

                        IAEItemStack extractedItem = itemStorage
                            .extractItems(ias, Actionable.MODULATE, new PlayerSource(player, null));
                        if (extractedItem != null && extractedItem.getStackSize() > 0) {
                            player.inventory.setInventorySlotContents(slotToInsert, extractedItem.getItemStack());

                            if (!player.inventory.addItemStackToInventory(ItemStack.copyItemStack(is))) {
                                IAEItemStack old_ias = AEApi.instance().storage().createItemStack(is);
                                IAEItemStack injectedItem = itemStorage.injectItems(old_ias, Actionable.MODULATE, new PlayerSource(player, null));
                                if (injectedItem != null && injectedItem.getStackSize() > 0) {
                                    player.entityDropItem(injectedItem.getItemStack(), 0);
                                }
                            }
                        }
                    }
                } else {
                    // Fill with full stack
                    IAEItemStack ias = AEApi.instance().storage().createItemStack(itemToPick);
                    ias.setStackSize(maxSize);

                    IAEItemStack extractedItem = itemStorage
                        .extractItems(ias, Actionable.MODULATE, new PlayerSource(player, null));
                    if (extractedItem != null) {
                        player.inventory.setInventorySlotContents(slotToInsert, extractedItem.getItemStack());
                    }
                }
            }
        }

        private static int getSlotToInsert(ItemStack itemToPick, InventoryPlayer inventory) {
            int currentSlot = inventory.currentItem;

            for (int i = 0; i < 9; i++) {
                if (inventory.mainInventory[i] == null || itemToPick.isItemEqual(inventory.mainInventory[i])) return i;
            }

            return currentSlot;
        }
    }
}
