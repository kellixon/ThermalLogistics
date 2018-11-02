package astavie.thermallogistics.event;

import astavie.thermallogistics.process.IProcess;
import astavie.thermallogistics.util.IProcessLoader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.LinkedList;
import java.util.List;

public class EventHandler {

	public static final List<IProcessLoader> LOADERS = new LinkedList<>();
	public static final List<IProcess> PROCESSES = new LinkedList<>();

	@SubscribeEvent
	public void onTick(TickEvent.ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.START) {
			LOADERS.forEach(IProcessLoader::loadProcesses);
			LOADERS.clear();
		} else if (event.phase == TickEvent.Phase.END) {
			// Remove processes that are done or have failed
			for (int i = 0; i < PROCESSES.size(); i++) {
				IProcess process = PROCESSES.get(i);
				if (process.isDone()) {
					process.remove();
					i--;
				}
				if (process.hasFailed()) {
					process.fail();
					process.remove();
					i--;
				}
			}

			// Update processes
			//noinspection ForLoopReplaceableByForEach
			for (int i = 0, processesSize = PROCESSES.size(); i < processesSize; i++) {
				IProcess process = PROCESSES.get(i);
				if (process.isLoaded() && !process.hasFailed())
					process.update();
			}
		}
	}

}
