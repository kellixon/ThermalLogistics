package astavie.thermallogistics.attachment;

import astavie.thermallogistics.ThermalLogistics;
import astavie.thermallogistics.event.EventHandler;
import astavie.thermallogistics.item.ItemRequester;
import astavie.thermallogistics.process.IProcess;
import astavie.thermallogistics.process.ProcessFluid;
import astavie.thermallogistics.proxy.ProxyClient;
import astavie.thermallogistics.util.IDestination;
import astavie.thermallogistics.util.IProcessLoader;
import codechicken.lib.fluid.FluidUtils;
import codechicken.lib.render.CCRenderState;
import codechicken.lib.vec.Translation;
import codechicken.lib.vec.Vector3;
import codechicken.lib.vec.uv.IconTransformation;
import cofh.core.util.helpers.FluidHelper;
import cofh.thermaldynamics.duct.Attachment;
import cofh.thermaldynamics.duct.attachments.retriever.RetrieverFluid;
import cofh.thermaldynamics.duct.attachments.servo.ServoFluid;
import cofh.thermaldynamics.duct.fluid.DuctUnitFluid;
import cofh.thermaldynamics.duct.fluid.FluidTankGrid;
import cofh.thermaldynamics.duct.fluid.GridFluid;
import cofh.thermaldynamics.duct.tiles.TileGrid;
import cofh.thermaldynamics.render.RenderDuct;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.util.*;

public class RequesterFluid extends RetrieverFluid implements IDestination<DuctUnitFluid, FluidStack>, IProcessLoader {

	public static final ResourceLocation ID = new ResourceLocation(ThermalLogistics.MODID, "requester_fluid");

	private final Set<FluidStack> leftovers = new HashSet<>();
	private final List<ProcessFluid> processes = new LinkedList<>();
	private NBTTagList _processes;

	public RequesterFluid(TileGrid tile, byte side) {
		super(tile, side);
	}

	public RequesterFluid(TileGrid tile, byte side, int type) {
		super(tile, side, type);
		filter.handleFlagByte(24); // Whitelist by default
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);

		NBTTagList leftovers = tag.getTagList("leftovers", Constants.NBT.TAG_COMPOUND);
		for (int i = 0; i < leftovers.tagCount(); i++)
			this.leftovers.add(FluidStack.loadFluidStackFromNBT(leftovers.getCompoundTagAt(i)));

		_processes = tag.getTagList("Processes", Constants.NBT.TAG_COMPOUND);
		EventHandler.LOADERS.add(this);
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		super.writeToNBT(tag);

		NBTTagList leftovers = new NBTTagList();
		for (FluidStack stack: this.leftovers)
			leftovers.appendTag(stack.writeToNBT(new NBTTagCompound()));

		NBTTagList processes = new NBTTagList();
		for (IProcess process : this.processes)
			processes.appendTag(process.save());

		tag.setTag("leftovers", leftovers);
		tag.setTag("Processes", processes);
	}

	@Override
	public ResourceLocation getId() {
		return ID;
	}

	@Override
	public ItemStack getPickBlock() {
		return new ItemStack(ThermalLogistics.requester, 1, type);
	}

	@Override
	public String getName() {
		return "item.logistics.requester." + ItemRequester.NAMES[type] + ".name";
	}

	@Override
	public void loadProcesses() {
		if (_processes != null) {
			for (int i = 0; i < _processes.tagCount(); i++) {
				ProcessFluid process = new ProcessFluid(baseTile.world(), _processes.getCompoundTagAt(i));
				process.setDestination(this);
				processes.add(process);
			}
			_processes = null;
		}
	}

	@Override
	public void tick(int pass) {
		GridFluid grid = fluidDuct.getGrid();
		if (pass != 1 || grid == null || !isPowered || !isValidInput)
			return;

		if (processes.size() > 0) {
			if (!isPowered || !isValidInput) {
				processes.forEach(IProcess::remove);
				processes.clear();
			} else {
				processes.removeIf(IProcess::isRemoved);
			}
			baseTile.markChunkDirty();
		}

		FluidTankGrid tank = grid.myTank;
		int maxInput = (int) Math.ceil(tank.fluidThroughput * ServoFluid.throttle[type]);
		int c = maxInput;

		if (tank.getFluid() != null)
			if (!fluidPassesFiltering(tank.getFluid()))
				return;
		Set<CrafterFluid> crafters = new HashSet<>();
		for (Iterator<DuctUnitFluid> iterator = grid.nodeSet.iterator(); iterator.hasNext() && maxInput > 0; ) {
			DuctUnitFluid fluidDuct = iterator.next();
			for (int k = 0; k < 6 && maxInput > 0; k++) {
				int side = (k + fluidDuct.internalSideCounter) % 6;
				DuctUnitFluid.Cache cache = fluidDuct.tileCache[side];
				if (cache == null || (!fluidDuct.isOutput(side) && !fluidDuct.isInput(side)))
					continue;

				Attachment attachment = fluidDuct.parent.getAttachment(side);
				if (attachment != null)
					if (attachment instanceof CrafterFluid) {
						crafters.add((CrafterFluid) attachment);
						continue;
					} else if (!attachment.canSend())
						continue;

				IFluidHandler handler = cache.getHandler(side ^ 1);
				if (handler == null)
					continue;

				FluidStack drainFluid = handler.drain(maxInput, false);
				if (drainFluid != null) {
					int input = tank.fill(drainFluid, false);
					if (fluidPassesFiltering(drainFluid)) {
						maxInput -= tank.fill(handler.drain(input, true), true);
						if (grid.toDistribute > 0 && tank.getFluid() != null) {
							GridFluid otherGrid = fluidDuct.getGrid();
							if (otherGrid != null)
								this.fluidDuct.transfer(side, Math.min(otherGrid.myTank.getFluid().amount, otherGrid.toDistribute), false, otherGrid.myTank.getFluid(), true);
						}
					}
				}
			}
		}
		if (maxInput == c) {
			// No fluid found, let's see if any process has some leftovers
			for (CrafterFluid crafter : crafters) {
				for (FluidStack output : crafter.outputs) {
					if (output == null || !fluidPassesFiltering(output))
						continue;

					int amount = getFluidHandler().fill(FluidUtils.copy(output, Integer.MAX_VALUE), false);
					for (ProcessFluid process : processes) {
						FluidStack compare = process.getOutput();
						if (FluidHelper.isFluidEqual(compare, output))
							amount -= compare.amount;
					}
					for (FluidStack stack : leftovers)
						if (FluidHelper.isFluidEqual(stack, output))
							amount -= stack.amount;

					// Alright, let's do this!
					output = crafter.registerLeftover(FluidUtils.copy(output, amount), this, false);
					baseTile.markChunkDirty();

					for (FluidStack leftover : leftovers) {
						if (FluidHelper.isFluidEqual(output, leftover)) {
							leftover.amount += output.amount;
							return;
						}
					}
					leftovers.add(output.copy());
					return;
				}
			}

			for (CrafterFluid crafter : crafters) {
				a:
				for (FluidStack fluid: crafter.outputs) {
					if (fluid == null || !fluidPassesFiltering(fluid))
						continue;

					fluid = fluid.copy();
					int amount = fluid.amount;
					for (ProcessFluid process : processes) {
						if (process.getCrafter() == crafter && process.isStuck())
							continue a;
						FluidStack compare = process.getOutput();
						if (FluidHelper.isFluidEqual(compare, fluid))
							fluid.amount += compare.amount;
					}
					for (FluidStack stack : leftovers)
						if (FluidHelper.isFluidEqual(stack, fluid))
							fluid.amount += stack.amount;

					if (getFluidHandler().fill(fluid, false) < fluid.amount)
						continue;

					// Alright, let's do this!
					fluid.amount = amount;
					processes.add(new ProcessFluid(this, crafter, fluid, 1));
					baseTile.markChunkDirty();
					return;
				}
			}
		}
	}

	@Override
	public boolean render(IBlockAccess world, BlockRenderLayer layer, CCRenderState ccRenderState) {
		if (layer != BlockRenderLayer.SOLID)
			return false;
		Translation trans = Vector3.fromTileCenter(baseTile).translation();
		RenderDuct.modelConnection[isPowered ? 1 : 2][side].render(ccRenderState, trans, new IconTransformation(ProxyClient.REQUESTER[stuffed ? 1 : 0][type]));
		return true;
	}

	@Override
	public DuctUnitFluid getDuct() {
		return fluidDuct;
	}

	@Override
	public byte getSide() {
		return 0;
	}

	@Override
	public int getType() {
		return 0;
	}

	@Override
	public boolean isInvalid() {
		return baseTile.isInvalid() || baseTile.getAttachment(side) != this;
	}

	@Override
	public boolean isTick() {
		return true;
	}

	@Override
	public void removeLeftover(FluidStack leftover) {
		Iterator<FluidStack> iterator = leftovers.iterator();
		while (iterator.hasNext()) {
			FluidStack next = iterator.next();
			if (FluidHelper.isFluidEqual(next, leftover)) {
				next.amount -= leftover.amount;
				if (next.amount <= 0)
					iterator.remove();
				return;
			}
		}
	}

}