package com.github.drewchase;

import com.github.drewchase.storage.InventoryStoreManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Coffer implements ModInitializer {
	public static final String MOD_ID = "coffer";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Bind the UUID-keyed inventory side store to the running server's save directory so the
		// block-entity storage mixins can reach it. Cleared on shutdown to avoid leaking across
		// integrated-server sessions.
		ServerLifecycleEvents.SERVER_STARTING.register(InventoryStoreManager::bind);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> InventoryStoreManager.unbind());
	}
}