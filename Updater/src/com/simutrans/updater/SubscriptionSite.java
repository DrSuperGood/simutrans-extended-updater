package com.simutrans.updater;

import java.util.LinkedList;
import java.util.function.Consumer;

public class SubscriptionSite<param> {
	private final LinkedList<Consumer<param>> subscribers = new LinkedList<Consumer<param>>();
	
	public final void addSubscription(final Consumer<param> handler) {
		subscribers.add(handler);
	}
	
	public final void removeSubscription(final Consumer<param> handler) {
		subscribers.remove(handler);
	}
	
	public final void clearSubscriptions() {
		subscribers.clear();
	}
	
	protected final void notifySubscribers(final param arg) {
		subscribers.forEach(sub -> sub.accept(arg));
	}
}
