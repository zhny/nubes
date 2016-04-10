package com.github.aesteve.vertx.nubes.reflections;

import com.github.aesteve.vertx.nubes.services.ServiceRegistry;
import io.vertx.ext.web.Router;

import java.lang.reflect.Field;

import com.github.aesteve.vertx.nubes.Config;

abstract class AbstractInjectionFactory {

	Config config;

	void injectServicesIntoController(Router router, Object instance) throws IllegalAccessException {
		final ServiceRegistry serviceRegistry = config.getServiceRegistry();
		for (Field field : instance.getClass().getDeclaredFields()) {
			Object service = serviceRegistry.get(field);
			setFieldAccessible(router, instance, service, field);
		}
		for (Field field : instance.getClass().getSuperclass().getDeclaredFields()) {
			Object service = serviceRegistry.get(field);
			setFieldAccessible(router, instance, service, field);
		}
	}

	private void setFieldAccessible(Router router, Object instance, Object service, Field field) throws IllegalAccessException {
		if (service != null) {
			field.setAccessible(true);
			field.set(instance, service);
		} else if (field.getType().equals(Router.class)) {
			field.setAccessible(true);
			field.set(instance, router);
		}

	}
}
