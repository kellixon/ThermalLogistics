package astavie.thermallogistics.gui.client.element;

import cofh.core.gui.GuiContainerCore;
import cofh.core.gui.element.ElementButtonBase;
import cofh.core.util.helpers.StringHelper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

public class ElementButtonIcon extends ElementButtonBase {

	private final TextureAtlasSprite icon;
	private final String hovering;
	private final Runnable click;

	public ElementButtonIcon(GuiContainerCore gui, int x, int y, TextureAtlasSprite icon, String hovering, Runnable click) {
		super(gui, x, y, 16, 16);
		this.icon = icon;
		this.hovering = hovering;
		this.click = click;
	}

	@Override
	public void drawBackground(int mouseX, int mouseY, float gameTicks) {
		gui.drawButton(icon, posX, posY, intersectsWith(mouseX, mouseY) ? 1 : 0);
	}

	@Override
	public void drawForeground(int mouseX, int mouseY) {
		if (intersectsWith(mouseX, mouseY))
			gui.drawHoveringText(StringHelper.localize(hovering), mouseX, mouseY);
	}

	@Override
	public void onClick() {
		click.run();
	}

}
