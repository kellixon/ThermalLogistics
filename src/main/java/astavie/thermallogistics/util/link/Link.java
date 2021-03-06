package astavie.thermallogistics.util.link;

import astavie.thermallogistics.gui.client.tab.TabLink;
import astavie.thermallogistics.proxy.ProxyClient;
import astavie.thermallogistics.util.delegate.DelegateClientItem;
import astavie.thermallogistics.util.delegate.IDelegateClient;
import astavie.thermallogistics.util.reference.CrafterReference;
import cofh.core.gui.GuiContainerCore;
import net.minecraft.item.ItemStack;

import java.util.List;

public class Link<I> implements ILink {

	public final CrafterReference<?> reference;
	public final ItemStack crafter;
	public final I input, output;
	public final boolean inputs, outputs;

	public final IDelegateClient<I, ?> delegate;

	public Link(CrafterReference<?> reference, ItemStack crafter, I input, boolean inputs, I output, boolean outputs, IDelegateClient<I, ?> delegate) {
		this.reference = reference;
		this.crafter = crafter;
		this.input = input;
		this.inputs = inputs;
		this.output = output;
		this.outputs = outputs;
		this.delegate = delegate;
	}

	@Override
	public void drawSummary(TabLink tab, int x, int y, int mouseX, int mouseY) {
		GuiContainerCore gui = tab.getContainerScreen();

		gui.drawItemStack(crafter, x, y, false, null);

		if (input != null)
			delegate.drawStack(gui, x + 18, y, input);
		if (inputs)
			gui.getFontRenderer().drawString("...", x + 37, y + 4, tab.textColor);

		gui.drawIcon(ProxyClient.ICON_ARROW_RIGHT, x + 44, y);

		if (output != null)
			delegate.drawStack(gui, x + 62, y, output);
		if (outputs)
			gui.getFontRenderer().drawString("...", x + 81, y + 4, tab.textColor);
	}

	@Override
	public void addTooltip(TabLink tab, int x, int y, int mouseX, int mouseY, List<String> list) {
		GuiContainerCore gui = tab.getContainerScreen();

		if (mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17)
			DelegateClientItem.INSTANCE.addTooltip(gui, crafter, list);

		if (!delegate.isNull(input) && mouseX >= x + 17 && mouseX < x + 35 && mouseY >= y - 1 && mouseY < y + 17)
			delegate.addTooltip(gui, input, list);

		if (!delegate.isNull(output) && mouseX >= x + 61 && mouseX < x + 79 && mouseY >= y - 1 && mouseY < y + 17)
			delegate.addTooltip(gui, output, list);
	}

	@Override
	public CrafterReference<?> getReference() {
		return reference;
	}

}
