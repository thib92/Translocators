package codechicken.translocator.tile;

import codechicken.lib.data.MCDataInput;
import codechicken.lib.data.MCDataOutput;
import codechicken.lib.inventory.InventoryUtils;
import codechicken.lib.packet.ICustomPacketTile;
import codechicken.lib.packet.PacketCustom;
import codechicken.lib.raytracer.IndexedCuboid6;
import codechicken.lib.vec.*;
import codechicken.translocator.network.TranslocatorSPH;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.LinkedList;
import java.util.List;

import static codechicken.lib.vec.Vector3.center;

public class TileCraftingGrid extends TileEntity implements ICustomPacketTile, ITickable, IIndexedCuboidProvider {
    public ItemStack[] items = new ItemStack[9];
    public ItemStack result = null;
    public int rotation = 0;

    public int timeout = 400;//20 seconds

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setTag("items", InventoryUtils.writeItemStacksToTag(items));
        tag.setInteger("timeout", timeout);
        return tag;
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        InventoryUtils.readItemStacksFromTag(items, tag.getTagList("items", 10));
        timeout = tag.getInteger("timeout");
    }

    @Override
    public void update() {
        if (!worldObj.isRemote) {
            timeout--;
            if (timeout == 0) {
                dropItems();
                worldObj.setBlockToAir(getPos());
            }
        }
    }

    public void dropItems() {
        Vector3 drop = Vector3.fromTileCenter(this);
        for (ItemStack item : items) {
            if (item != null) {
                InventoryUtils.dropItem(item, worldObj, drop);
            }
        }
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        PacketCustom packet = new PacketCustom(TranslocatorSPH.channel, 3);
        writeToPacket(packet);
        return packet.toTilePacket(getPos());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        PacketCustom packet = new PacketCustom(TranslocatorSPH.channel, 3);
        writeToPacket(packet);
        return packet.toNBTTag(super.getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromPacket(PacketCustom.fromTilePacket(pkt));
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromPacket(PacketCustom.fromNBTTag(tag));
    }

    @Override
    public void writeToPacket(MCDataOutput packet) {
        packet.writeByte(rotation);
        for (ItemStack item : items) {
            packet.writeItemStack(item);
        }
    }

    @Override
    public void readFromPacket(MCDataInput packet) {
        rotation = packet.readUByte();

        for (int i = 0; i < 9; i++) {
            items[i] = packet.readItemStack();
        }

        updateResult();
    }

    public void activate(int subHit, EntityPlayer player) {
        ItemStack held = player.inventory.getCurrentItem();
        if (held == null) {
            if (items[subHit] != null) {
                giveOrDropItem(items[subHit], player);
            }
            items[subHit] = null;
        } else {
            if (!InventoryUtils.areStacksIdentical(held, items[subHit])) {
                ItemStack old = items[subHit];
                items[subHit] = InventoryUtils.copyStack(held, 1);
                player.inventory.decrStackSize(player.inventory.currentItem, 1);

                if (old != null) {
                    giveOrDropItem(old, player);
                }
            }
        }

        timeout = 2400;
        IBlockState state = worldObj.getBlockState(getPos());
        worldObj.notifyBlockUpdate(getPos(), state, state, 3);
        markDirty();
    }

    private void updateResult() {
        InventoryCrafting craftMatrix = getCraftMatrix();

        for (int i = 0; i < 4; i++) {
            ItemStack mresult = CraftingManager.getInstance().findMatchingRecipe(craftMatrix, worldObj);
            if (mresult != null) {
                result = mresult;
                return;
            }

            rotateItems(craftMatrix);
        }

        result = null;
    }

    private void giveOrDropItem(ItemStack stack, EntityPlayer player) {
        if (player.inventory.addItemStackToInventory(stack)) {
            player.inventoryContainer.detectAndSendChanges();
        } else {
            InventoryUtils.dropItem(stack, worldObj, Vector3.fromTileCenter(this));
        }
    }

    public void craft(EntityPlayer player) {
        InventoryCrafting craftMatrix = getCraftMatrix();

        for (int i = 0; i < 4; i++) {
            ItemStack mresult = CraftingManager.getInstance().findMatchingRecipe(craftMatrix, worldObj);
            if (mresult != null) {
                doCraft(mresult, craftMatrix, player);
                break;
            }

            rotateItems(craftMatrix);
        }
        player.swingArm(EnumHand.MAIN_HAND);
        dropItems();
        worldObj.setBlockToAir(getPos());
    }

    private InventoryCrafting getCraftMatrix() {
        InventoryCrafting craftMatrix = new InventoryCrafting(new Container() {
            @Override
            public boolean canInteractWith(EntityPlayer entityplayer) {
                return true;
            }
        }, 3, 3);

        for (int i = 0; i < 9; i++) {
            craftMatrix.setInventorySlotContents(i, items[i]);
        }

        return craftMatrix;
    }

    private void doCraft(ItemStack mresult, InventoryCrafting craftMatrix, EntityPlayer player) {
        giveOrDropItem(mresult, player);

        FMLCommonHandler.instance().firePlayerCraftingEvent(player, mresult, craftMatrix);
        mresult.onCrafting(worldObj, player, mresult.stackSize);

        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = craftMatrix.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }

            craftMatrix.decrStackSize(slot, 1);
            if (stack.getItem().hasContainerItem(stack)) {
                ItemStack container = stack.getItem().getContainerItem(stack);

                if (container != null) {
                    if (container.isItemStackDamageable() && container.getItemDamage() > container.getMaxDamage()) {
                        container = null;
                    }

                    craftMatrix.setInventorySlotContents(slot, container);
                }
            }
        }

        for (int i = 0; i < 9; i++) {
            items[i] = craftMatrix.getStackInSlot(i);
        }
    }

    private void rotateItems(InventoryCrafting inv) {
        int[] slots = new int[] { 0, 1, 2, 5, 8, 7, 6, 3 };
        ItemStack[] arrangement = new ItemStack[9];
        arrangement[4] = inv.getStackInSlot(4);

        for (int i = 0; i < 8; i++) {
            arrangement[slots[(i + 2) % 8]] = inv.getStackInSlot(slots[i]);
        }

        for (int i = 0; i < 9; i++) {
            inv.setInventorySlotContents(i, arrangement[i]);
        }
    }

    public void onPlaced(EntityLivingBase entity) {
        rotation = (int) (entity.rotationYaw * 4 / 360 + 0.5D) & 3;
    }

    @Override
    public IndexedCuboid6 getBlockBounds() {
        return new IndexedCuboid6(0, new Cuboid6(0, 0, 0, 1, 0.005, 1));
    }

    @Override
    public List<IndexedCuboid6> getIndexedCuboids() {
        LinkedList<IndexedCuboid6> parts = new LinkedList<IndexedCuboid6>();

        for (int i = 0; i < 9; i++) {
            Cuboid6 box = new Cuboid6(1 / 16D, 0, 1 / 16D, 5 / 16D, 0.01, 5 / 16D).apply(new Translation((i % 3) * 5 / 16D, 0, (i / 3) * 5 / 16D).with(Rotation.quarterRotations[rotation].at(center)).with(new Translation(new Vector3(pos))));

            parts.add(new IndexedCuboid6(i + 1, box));
        }

        return parts;
    }
}
