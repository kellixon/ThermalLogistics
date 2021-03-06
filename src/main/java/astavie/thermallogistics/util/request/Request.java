package astavie.thermallogistics.util.request;

import astavie.thermallogistics.event.EventHandler;
import astavie.thermallogistics.util.IRequester;
import astavie.thermallogistics.util.delegate.IDelegate;
import astavie.thermallogistics.util.reference.RequesterReference;
import cofh.core.network.PacketBase;
import cofh.thermaldynamics.duct.tiles.DuctUnit;
import com.google.common.collect.Iterables;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class Request<T extends DuctUnit<T, ?, ?>, I> implements IRequest<T, I> {

	private final RequesterReference<IRequester<T, I>> start;
	private final List<I> stacks = NonNullList.create();

	private ItemStack block = ItemStack.EMPTY;
	private ItemStack attachment = ItemStack.EMPTY;

	private long birth;

	public Request(@Nullable IRequester<T, I> start, Collection<I> stacks) {
		this.birth = EventHandler.time;
		this.start = start == null ? null : new RequesterReference<>(start);
		this.stacks.addAll(stacks);
	}

	public Request(@Nullable IRequester<T, I> start, I stack) {
		this(start, Collections.singleton(stack));
	}

	private Request(@Nullable RequesterReference<IRequester<T, I>> start, Collection<I> stacks) {
		this.birth = EventHandler.time;
		this.start = start;
		this.stacks.addAll(stacks);
	}

	public Request(IDelegate<I> delegate, NBTTagCompound tag) {
		this.birth = tag.getLong("birth");

		if (tag.hasKey("start")) {
			NBTTagCompound n = tag.getCompoundTag("start");
			this.start = new RequesterReference<>(tag.getInteger("dim"), new BlockPos(n.getInteger("x"), n.getInteger("y"), n.getInteger("z")), n.getByte("side"), n.hasKey("index") ? n.getInteger("index") : -1);
		} else
			this.start = null;

		NBTTagList list = tag.getTagList("stacks", Constants.NBT.TAG_COMPOUND);
		for (int i = 0; i < list.tagCount(); i++)
			stacks.add(delegate.readNbt(list.getCompoundTagAt(i)));
	}

	public Request(IDelegate<I> delegate, PacketBase packet) {
		this.birth = packet.getLong();

		if (packet.getBool())
			this.start = new RequesterReference<>(packet.getInt(), new BlockPos(packet.getInt(), packet.getInt(), packet.getInt()), (byte) packet.getInt(), packet.getInt());
		else
			this.start = null;

		if (packet.getBool()) {
			block = packet.getItemStack();
			if (packet.getBool())
				attachment = packet.getItemStack();
		}

		int size = packet.getInt();
		for (int i = 0; i < size; i++)
			stacks.add(delegate.readPacket(packet));
	}

	public static <T extends DuctUnit<T, ?, ?>, I> Request<T, I> combine(IDelegate<I> delegate, IRequest<T, I> a, IRequest<T, I> b) {
		List<I> list = new LinkedList<>();
		for (I stack : Iterables.concat(a.getStacks(), b.getStacks()))
			list.add(delegate.copy(stack));
		delegate.truncate(list);

		Request<T, I> combine = new Request<>(a.getStart(), list);
		combine.birth = Math.min(a.getBirth(), b.getBirth());
		return combine;
	}

	@Override
	public List<I> getStacks() {
		return stacks;
	}

	@Override
	@Nullable
	public IRequester<T, I> getStart() {
		return start == null ? null : start.getRequester();
	}

	@Nullable
	@Override
	public RequesterReference<IRequester<T, I>> getStartReference() {
		return start;
	}

	@Override
	public ItemStack getBlock() {
		return block;
	}

	@Override
	public ItemStack getAttachment() {
		return attachment;
	}

	@Override
	public long getBirth() {
		return birth;
	}

	public Request<T, I> copy(IDelegate<I> delegate) {
		Request<T, I> clone = new Request<>(start, stacks.stream().map(delegate::copy).collect(Collectors.toList()));
		clone.birth = birth;
		return clone;
	}

	public Request<T, I> copyFaceless(IDelegate<I> delegate) {
		Request<T, I> clone = new Request<>((RequesterReference<IRequester<T, I>>) null, stacks.stream().map(delegate::copy).collect(Collectors.toList()));
		clone.birth = birth;
		return clone;
	}

}
