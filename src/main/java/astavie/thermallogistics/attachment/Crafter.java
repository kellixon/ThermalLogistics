package astavie.thermallogistics.attachment;

import astavie.thermallogistics.ThermalLogistics;
import astavie.thermallogistics.compat.ICrafterWrapper;
import astavie.thermallogistics.event.EventHandler;
import astavie.thermallogistics.gui.client.GuiCrafter;
import astavie.thermallogistics.gui.container.ContainerCrafter;
import astavie.thermallogistics.item.ItemCrafter;
import astavie.thermallogistics.process.IProcess;
import astavie.thermallogistics.proxy.ProxyClient;
import astavie.thermallogistics.util.IProcessHolder;
import astavie.thermallogistics.util.IRequester;
import astavie.thermallogistics.util.link.ILink;
import astavie.thermallogistics.util.reference.CrafterReference;
import astavie.thermallogistics.util.request.Requests;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.vec.Cuboid6;
import codechicken.lib.vec.Translation;
import codechicken.lib.vec.Vector3;
import codechicken.lib.vec.uv.IconTransformation;
import cofh.api.core.IPortableData;
import cofh.core.block.TileCore;
import cofh.core.network.PacketBase;
import cofh.core.network.PacketHandler;
import cofh.core.network.PacketTileInfo;
import cofh.core.util.helpers.BlockHelper;
import cofh.core.util.helpers.ServerHelper;
import cofh.thermaldynamics.ThermalDynamics;
import cofh.thermaldynamics.block.BlockDuct.ConnectionType;
import cofh.thermaldynamics.duct.Attachment;
import cofh.thermaldynamics.duct.attachments.filter.FilterLogic;
import cofh.thermaldynamics.duct.attachments.filter.IFilterAttachment;
import cofh.thermaldynamics.duct.tiles.DuctUnit;
import cofh.thermaldynamics.duct.tiles.TileGrid;
import cofh.thermaldynamics.gui.GuiHandler;
import cofh.thermaldynamics.render.RenderDuct;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.*;

public abstract class Crafter<P extends IProcess<P, T, I>, T extends DuctUnit<T, ?, ?>, I> extends Attachment implements IProcessHolder<P, T, I>, IPortableData, IFilterAttachment {

	public final List<Integer> flags = new LinkedList<>();
	public final boolean[] values = FilterLogic.defaultflags.clone();

	public final List<P> processes = new ArrayList<>();

	// Client-only
	public final List<Requests<T, I>> requests = new LinkedList<>();
	public final List<ILink> links = new LinkedList<>(); // haha "linked" list

	public Set<CrafterReference> linked = new HashSet<>();

	public int type;

	public TileEntity tile;
	private ICrafterWrapper wrapper;

	private int flagByte;
	private NBTTagList _linked = new NBTTagList();

	public Crafter(TileGrid tile, byte side) {
		super(tile, side);
	}

	public Crafter(TileGrid tile, byte side, int type) {
		super(tile, side);
		this.type = type;
		updateFlags();
	}

	public static Crafter readCrafter(NBTTagCompound tag) {
		TileEntity tile = EventHandler.getWorld(tag.getInteger("dim")).getTileEntity(new BlockPos(tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z")));
		if (tile instanceof TileGrid) {
			Attachment attachment = ((TileGrid) tile).getAttachment(tag.getByte("side"));
			if (attachment instanceof Crafter)
				return (Crafter) attachment;
		}
		return null;
	}

	public static void writeCrafter(Crafter crafter, NBTTagCompound tag) {
		tag.setInteger("dim", crafter.baseTile.world().provider.getDimension());
		tag.setInteger("x", crafter.baseTile.x());
		tag.setInteger("y", crafter.baseTile.y());
		tag.setInteger("z", crafter.baseTile.z());
		tag.setByte("side", crafter.side);
	}

	private static void writeCrafter(CrafterReference reference, NBTTagCompound tag) {
		tag.setInteger("dim", reference.dim);
		tag.setInteger("x", reference.pos.getX());
		tag.setInteger("y", reference.pos.getY());
		tag.setInteger("z", reference.pos.getZ());
		tag.setByte("side", reference.side);
	}

	public abstract P createLinkedProcess(int sum);

	public abstract int amountRequired(I item);

	public abstract I[] getInputs();

	public abstract I[] getOutputs();

	@Override
	public TileCore getTile() {
		return baseTile;
	}

	@Override
	public Set<Crafter> getLinked() {
		Set<Crafter> linked = new LinkedHashSet<>();
		for (CrafterReference reference : this.linked) {
			Crafter<?, ?, ?> crafter = reference.getCrafter();
			if (crafter != null)
				linked.add(crafter);
		}
		return linked;
	}

	@Override
	public boolean isInvalid() {
		return baseTile.isInvalid() || baseTile.getAttachment(side) != this;
	}

	@Override
	public void addProcess(P process, int index) {
		if (index < 0) {
			processes.add(process);
		} else {
			while (index >= processes.size())
				processes.add(null);
			processes.set(index, process);
		}
	}

	@Override
	public void removeProcess(P process) {
		processes.remove(process);
	}

	@Override
	public List<P> getProcesses() {
		return processes;
	}

	@Override
	public void postLoad() {
		processes.removeIf(Objects::isNull);
	}

	@Override
	public byte getSide() {
		return side;
	}

	@Override
	public int getType() {
		return type;
	}

	@Override
	public int amountRequired(P process, I item) {
		return amountRequired(item);
	}

	@Override
	public boolean canSend() {
		return false;
	}

	@Override
	@SuppressWarnings("unchecked")
	public T getDuct() {
		return (T) baseTile.getDuct(tickUnit());
	}

	@Override
	public void tick(int pass) {
		if (_linked != null) {
			linked = new LinkedHashSet<>();
			linked.add(new CrafterReference<>(this));
			for (int i = 0; i < _linked.tagCount(); i++) {
				Crafter<?, ?, ?> crafter = readCrafter(_linked.getCompoundTagAt(i));
				if (crafter != null) {
					this.linked.add(new CrafterReference<>(crafter));
					crafter.linked = this.linked;
					crafter._linked = null;
				}
			}
			_linked = null;
		}
		if (pass == 0) {
			if (linked.removeIf(c -> {
				if (c.isLoaded()) {
					Crafter crafter = c.getCrafter();
					return crafter == null || crafter.isInvalid();
				}
				return false;
			})) {
				processes.forEach(IProcess::setFailed);
			}
		}
	}

	@Override
	public void onNeighborChange() {
		TileEntity tile = BlockHelper.getAdjacentTileEntity(baseTile, side);
		if (tile != null && !isValidTile(tile))
			tile = null;

		if (tile != this.tile) {
			if (this.tile != null)
				this.processes.forEach(IProcess::setFailed);
			this.tile = tile;
			if (tile != null)
				this.wrapper = ThermalLogistics.getWrapper(tile);
			else
				this.wrapper = null;
		}
	}

	protected abstract boolean isValidTile(TileEntity tile);

	private int getFlagByte() {
		int t = 0;
		for (int i = 0; i < values.length; i++)
			if (values[i])
				t = t | (1 << i);
		return t;
	}

	private void handleFlagByte(int t) {
		for (int i = 0; i < values.length; i++)
			if (i > 0 && FilterLogic.canAlterFlag(baseTile.getDuctType().ductType, type, i)) // ignore whitelist
				values[i] = (t & (1 << i)) != 0;
			else
				values[i] = FilterLogic.defaultflags[i];
	}

	private void updateFlags() {
		flags.clear();
		for (int i = 1; i < FilterLogic.flagTypes.length; i++) // Start at 1 to ignore whitelist
			if (FilterLogic.canAlterFlag(baseTile.getDuctType().ductType, type, i))
				flags.add(i);
	}

	public boolean swapFlag(int flag) {
		if (flag == 0 || !FilterLogic.canAlterFlag(baseTile.getDuctType().ductType, type, flag)) // ignore whitelist
			return false;
		if (baseTile.world().isRemote) {
			PacketTileInfo packet = getNewPacket();
			packet.addByte(0);
			packet.addByte(flag);
			PacketHandler.sendToServer(packet);
		} else
			baseTile.markChunkDirty();
		values[flag] = !values[flag];
		return true;
	}

	public void autoInput() {
		if (baseTile.world().isRemote) {
			PacketTileInfo packet = getNewPacket();
			packet.addByte(1);
			PacketHandler.sendToServer(packet);
		} else if (wrapper != null) {
			setAutoInput(wrapper);
			baseTile.markChunkDirty();
		}
	}

	protected abstract void setAutoInput(ICrafterWrapper wrapper);

	public void autoOutput() {
		if (baseTile.world().isRemote) {
			PacketTileInfo packet = getNewPacket();
			packet.addByte(2);
			PacketHandler.sendToServer(packet);
		} else if (wrapper != null) {
			setAutoOutput(wrapper);
			baseTile.markChunkDirty();
		}
	}

	protected abstract void setAutoOutput(ICrafterWrapper wrapper);

	protected void addLink(PacketBase packet) {
		// id
		packet.addString(getId().toString());

		// location
		CrafterReference.write(this, packet);

		// appearance
		packet.addItemStack(getPickBlock());

		I input = null, output = null;
		boolean bI = false, bO = false;

		boolean b = false;
		for (I stack : getInputs()) {
			if (!getDelegate().isNull(stack)) {
				if (input == null)
					input = stack;
				if (b) {
					bI = true;
					break;
				} else b = true;
			}
		}

		b = false;
		for (I stack : getOutputs()) {
			if (!getDelegate().isNull(stack)) {
				if (output == null)
					output = stack;
				if (b) {
					bO = true;
					break;
				} else b = true;
			}
		}

		getDelegate().writePacket(packet, input);
		packet.addBool(bI);

		getDelegate().writePacket(packet, output);
		packet.addBool(bO);
	}

	private PacketBase getRequestsPacket() {
		PacketBase payload = getNewPacket();
		payload.addByte(5);
		return payload;
	}

	public void sendRequestsPacket() {
		PacketHandler.sendToServer(getRequestsPacket());
	}

	@Override
	public void handleInfoPacket(PacketBase payload, boolean isServer, EntityPlayer player) {
		byte message = payload.getByte();
		switch (message) {
			// SERVER
			case 0:
				swapFlag(payload.getByte());
				break;
			case 1:
				autoInput();
				break;
			case 2:
				autoOutput();
				break;
			case 3: {
				List<CrafterReference> crafters = new ArrayList<>(linked);
				crafters.remove(new CrafterReference<>(this));

				CrafterReference reference = crafters.get(payload.getInt());
				linked.remove(reference);

				Crafter<?, ?, ?> crafter = reference.getCrafter();
				crafter.linked = new LinkedHashSet<>();
				crafter.linked.add(reference);
				break;
			}

			// CLIENT
			case 4: {
				links.clear();
				int size = payload.getInt();
				for (int i = 0; i < size; i++)
					links.add(ProxyClient.getLink(new ResourceLocation(payload.getString()), payload));
				break;
			}
			case 5: {
				if (isServer) {
					PacketBase packet = getRequestsPacket();

					List<Requests<T, I>> requests = getRequests();
					packet.addInt(requests.size());
					for (Requests<T, I> request : requests)
						request.writePacket(getDelegate(), packet);

					PacketHandler.sendTo(packet, player);
				} else {
					requests.clear();
					int size = payload.getInt();
					for (int i = 0; i < size; i++)
						requests.add(new Requests<>(getDelegate(), payload));
					break;
				}
			}

			// SERVER / CLIENT
			default:
				handleInfoPacket(message, payload);
				break;
		}
	}

	protected abstract void handleInfoPacket(byte message, PacketBase payload);

	@Override
	public String getName() {
		return "item.logistics.crafter." + ItemCrafter.NAMES[type] + ".name";
	}

	@Override
	public boolean isNode() {
		return true;
	}

	@Nonnull
	@Override
	public ConnectionType getNeighborType() {
		return ConnectionType.DUCT;
	}

	@Override
	public Cuboid6 getCuboid() {
		return TileGrid.subSelection[side].copy();
	}

	@Override
	public List<ItemStack> getDrops() {
		LinkedList<ItemStack> drops = new LinkedList<>();
		drops.add(getPickBlock());
		return drops;
	}

	@Override
	public ItemStack getPickBlock() {
		return new ItemStack(ThermalLogistics.crafter, 1, type);
	}

	@Override
	public boolean respondsToSignalum() {
		return true;
	}

	@Override
	public List<Requests<T, I>> getRequests() {
		List<Requests<T, I>> list = new LinkedList<>();
		for (P process : processes)
			list.addAll(process.getRequests());
		return list;
	}

	@Override
	public void removeLeftover(IRequester<T, I> requester, I leftover) {
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		type = tag.getByte("type") % 5;
		updateFlags();

		readInventory(tag);
		_linked = tag.getTagList("Linked", Constants.NBT.TAG_COMPOUND);
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		tag.setByte("type", (byte) type);
		writeInventory(tag);

		NBTTagList linked = new NBTTagList();
		for (CrafterReference crafter : this.linked) {
			if (crafter.equals(new CrafterReference<>(this)))
				continue;
			NBTTagCompound link = new NBTTagCompound();
			writeCrafter(crafter, link);
			linked.appendTag(link);
		}
		tag.setTag("Linked", linked);
	}

	private void readInventory(NBTTagCompound tag) {
		readRecipe(tag);
		handleFlagByte(tag.getByte("Flags"));
	}

	private void writeInventory(NBTTagCompound tag) {
		writeRecipe(tag);
		tag.setByte("Flags", (byte) getFlagByte());
	}

	protected abstract void writeRecipe(NBTTagCompound tag);

	protected abstract void readRecipe(NBTTagCompound tag);

	@Override
	public void addDescriptionToPacket(PacketBase packet) {
		packet.addByte(type);
		try {
			NBTTagCompound tag = new NBTTagCompound();
			writeRecipe(tag);
			packet.writeNBT(tag);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void getDescriptionFromPacket(PacketBase packet) {
		type = packet.getByte();
		updateFlags();

		try {
			readRecipe(packet.readNBT());
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public Object getGuiClient(InventoryPlayer inventory) {
		return new GuiCrafter(inventory, this);
	}

	@Override
	public Object getGuiServer(InventoryPlayer inventory) {
		return new ContainerCrafter(inventory, this);
	}

	@Override
	public boolean openGui(EntityPlayer player) {
		if (ServerHelper.isServerWorld(baseTile.world())) {
			PacketTileInfo packet = getNewPacket();
			packet.addByte(4);
			packet.addInt(linked.size() - 1);
			for (CrafterReference crafter : this.linked) {
				if (crafter.equals(new CrafterReference<>(this)))
					continue;
				crafter.getCrafter().addLink(packet);
			}

			PacketHandler.sendTo(packet, player);
			player.openGui(ThermalDynamics.instance, GuiHandler.TILE_ATTACHMENT_ID + side, baseTile.getWorld(), baseTile.x(), baseTile.y(), baseTile.z());
		}
		return true;
	}

	@Override
	public void receiveGuiNetworkData(int i, int j) {
		handleFlagByte(j);
	}

	@Override
	public void sendGuiNetworkData(Container container, List<IContainerListener> listeners, boolean newListener) {
		int flagByte = getFlagByte();
		if (flagByte != this.flagByte || newListener)
			for (IContainerListener listener : listeners)
				listener.sendWindowProperty(container, 0, flagByte);
		this.flagByte = flagByte;
	}

	@Override
	public boolean render(IBlockAccess world, BlockRenderLayer layer, CCRenderState ccRenderState) {
		if (layer != BlockRenderLayer.SOLID)
			return false;
		Translation trans = Vector3.fromTileCenter(baseTile).translation();
		RenderDuct.modelConnection[1][side].render(ccRenderState, trans, new IconTransformation(ProxyClient.CRAFTER[0][type]));
		return true;
	}

	@Override
	public String getDataType() {
		return "Crafter";
	}

	@Override
	public void readPortableData(EntityPlayer player, NBTTagCompound tag) {
		readInventory(tag);
		onNeighborChange();
	}

	@Override
	public void writePortableData(EntityPlayer player, NBTTagCompound tag) {
		writeInventory(tag);
	}

	public abstract Cache createCache();

	public interface Cache {

		void detectAndSendChanges(EntityPlayer player);

	}

}
