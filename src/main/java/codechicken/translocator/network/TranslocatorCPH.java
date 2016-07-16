package codechicken.translocator.network;

import codechicken.core.ClientUtils;
import codechicken.lib.inventory.InventorySimple;
import codechicken.lib.packet.ICustomPacketTile;
import codechicken.lib.packet.PacketCustom;
import codechicken.lib.packet.PacketCustom.IClientPacketHandler;
import codechicken.translocator.client.gui.GuiTranslocator;
import codechicken.translocator.Translocator;
import codechicken.translocator.container.ContainerItemTranslocator;
import codechicken.translocator.tile.TileTranslocator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.FMLLog;

public class TranslocatorCPH implements IClientPacketHandler {
    public static Object channel = Translocator.instance;

    @Override
    public void handlePacket(PacketCustom packet, Minecraft mc, INetHandlerPlayClient handler) {
        switch (packet.getType()) {
        case 1:
        case 2:
        case 3:
            TileEntity tile = mc.theWorld.getTileEntity(packet.readCoord().pos());
            if (tile instanceof TileTranslocator) {
                ((TileTranslocator) tile).handlePacket(packet);
            }
            break;
        case 4:
            int windowId = packet.readUByte();
            GuiTranslocator gui = new GuiTranslocator(new ContainerItemTranslocator(new InventorySimple(9, packet.readUShort(), packet.readString()), mc.thePlayer.inventory));
            ClientUtils.openSMPGui(windowId, gui);
            break;
        case 5:
            mc.thePlayer.openContainer.putStackInSlot(packet.readUByte(), packet.readItemStack());
            break;
        }
    }
}