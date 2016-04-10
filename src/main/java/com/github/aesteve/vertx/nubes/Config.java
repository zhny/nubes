
package com.github.aesteve.vertx.nubes;

import com.github.aesteve.vertx.nubes.auth.AuthMethod;
import com.github.aesteve.vertx.nubes.context.RateLimit;
import com.github.aesteve.vertx.nubes.handlers.AnnotationProcessor;
import com.github.aesteve.vertx.nubes.handlers.AnnotationProcessorRegistry;
import com.github.aesteve.vertx.nubes.handlers.Processor;
import com.github.aesteve.vertx.nubes.marshallers.PayloadMarshaller;
import com.github.aesteve.vertx.nubes.reflections.adapters.ParameterAdapterRegistry;
import com.github.aesteve.vertx.nubes.reflections.factories.AnnotationProcessorFactory;
import com.github.aesteve.vertx.nubes.reflections.injectors.annot.AnnotatedParamInjector;
import com.github.aesteve.vertx.nubes.reflections.injectors.annot.AnnotatedParamInjectorRegistry;
import com.github.aesteve.vertx.nubes.reflections.injectors.typed.ParamInjector;
import com.github.aesteve.vertx.nubes.reflections.injectors.typed.TypedParamInjectorRegistry;
import com.github.aesteve.vertx.nubes.services.ServiceRegistry;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.auth.AuthProvider;
import io.vertx.ext.auth.jdbc.JDBCAuth;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.shiro.ShiroAuth;
import io.vertx.ext.auth.shiro.ShiroAuthOptions;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.templ.*;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Config {

	private static final Logger LOG = LoggerFactory.getLogger(Config.class);

	private Config() {
		bundlesByLocale = new HashMap<>();
		globalHandlers = new ArrayList<>();
		templateEngines = new HashMap<>();
		sockJSOptions = new SockJSHandlerOptions();
		marshallers = new HashMap<>();
		annotationHandlers = new HashMap<>();
		paramHandlers = new HashMap<>();
		typeProcessors = new HashMap<>();
		apRegistry = new AnnotationProcessorRegistry();
		typeInjectors = new TypedParamInjectorRegistry(this);
		aopHandlerRegistry = new HashMap<>();
	}

	private JsonObject json;
	private String srcPackage;
	private List<String> controllerPackages;
	private List<String> fixturePackages;
	private String verticlePackage;
	private String domainPackage;
	private RateLimit rateLimit;
	private String webroot;
	private String assetsPath;
	private String tplDir;
	private boolean displayErrors;
	private Vertx vertx;
	private AuthProvider authProvider;
	private AuthMethod authMethod;
	private String i18nDir;

	private AnnotationProcessorRegistry apRegistry;
	private Map<Class<? extends Annotation>, Set<Handler<RoutingContext>>> annotationHandlers;
	private Map<Class<?>, Processor> typeProcessors;
	private TypedParamInjectorRegistry typeInjectors;
	private AnnotatedParamInjectorRegistry annotInjectors;
	private ServiceRegistry serviceRegistry;
	private Map<Class<?>, Handler<RoutingContext>> paramHandlers;
	private Map<String, Handler<RoutingContext>> aopHandlerRegistry;
	private final Map<Locale, ResourceBundle> bundlesByLocale;
	private final List<Handler<RoutingContext>> globalHandlers;
	private final Map<String, TemplateEngine> templateEngines;
	private final SockJSHandlerOptions sockJSOptions;
	private Map<String, PayloadMarshaller> marshallers;

	/**
	 * TODO : check config instead of throwing exceptions
	 * TODO : we should be consistent on single/multiple values
	 * (controllers is an array, fixtures is a list, domain is a single value, verticle is a single value) : this is wrong
	 * 
	 * @param json
	 * @return config
	 */
	@SuppressWarnings("unchecked")
	public static Config fromJsonObject(JsonObject json, Vertx vertx) {
		Config instance = new Config();

		instance.json = json;
		instance.vertx = vertx;
		instance.srcPackage = json.getString("src-package");
		instance.i18nDir = json.getString("i18nDir", "web/i18n/");
		if (!instance.i18nDir.endsWith("/")) {
			instance.i18nDir = instance.i18nDir + "/";
		}
		JsonArray controllers = json.getJsonArray("controller-packages");
		if (controllers == null) {
			controllers = new JsonArray();
			if (instance.srcPackage != null) {
				controllers.add(instance.srcPackage + ".controllers");
			}
		}
		instance.controllerPackages = controllers.getList();

		instance.verticlePackage = json.getString("verticle-package");
		if (instance.verticlePackage == null && instance.srcPackage != null) {
			instance.verticlePackage = instance.srcPackage + ".verticles";
		}

		instance.domainPackage = json.getString("domain-package");
		if (instance.domainPackage == null && instance.srcPackage != null) {
			instance.domainPackage = instance.srcPackage + ".domains";
		}
		JsonArray fixtures = json.getJsonArray("fixture-packages");
		if (fixtures == null) {
			fixtures = new JsonArray();
			if (instance.srcPackage != null) {
				fixtures.add(instance.srcPackage + ".fixtures");
			}
		}
		instance.fixturePackages = fixtures.getList();

		// Register services included in config
		JsonObject services = json.getJsonObject("services", new JsonObject());
		instance.serviceRegistry = new ServiceRegistry(vertx, instance);
		services.forEach(entry -> {
			String name = entry.getKey();
			String className = (String) entry.getValue();
			try {
				Class<?> clazz = Class.forName(className);
				instance.serviceRegistry.registerService(name, clazz.newInstance());
			} catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		});

		// Register templateEngines for extensions added in config
		JsonArray templates = json.getJsonArray("templates", new JsonArray());
		if (templates.contains("hbs")) {
			instance.templateEngines.put("hbs", HandlebarsTemplateEngine.create());
		}
		if (templates.contains("jade")) {
			instance.templateEngines.put("jade", JadeTemplateEngine.create());
		}
		if (templates.contains("templ")) {
			instance.templateEngines.put("templ", MVELTemplateEngine.create());
		}
		if (templates.contains("thymeleaf")) {
			instance.templateEngines.put("html", ThymeleafTemplateEngine.create());
		}

		JsonObject rateLimitJson = json.getJsonObject("throttling");
		if (rateLimitJson != null) {
			int count = rateLimitJson.getInteger("count");
			int value = rateLimitJson.getInteger("time-frame");
			TimeUnit timeUnit = TimeUnit.valueOf(rateLimitJson.getString("time-unit"));
			instance.rateLimit = new RateLimit(count, value, timeUnit);
		}

		String auth = json.getString("auth-type");
		JsonObject authProperties = json.getJsonObject("auth-properties");

		// TODO : discuss it. I'm really not convinced about all the boilerplate needed in config (dbName only for JDBC, etc.)
		if (authProperties != null) {
			// For now, only JWT,Shiro and JDBC supported (same as for Vert.x web)
			switch (auth) {
				case "JWT":// For now only allow properties realm
					instance.authProvider = JWTAuth.create(vertx, authProperties);
					break;
				case "Shiro":
					ShiroAuth.create(vertx, new ShiroAuthOptions(authProperties));
					break;
				case "JDBC":
					String dbName = json.getString("db-name");
					Objects.requireNonNull(dbName);
					JDBCClient client = JDBCClient.createShared(vertx, authProperties, dbName);
					instance.authProvider = JDBCAuth.create(client);
					break;
				default:
					LOG.warn("Unknown type of auth : " + auth + " . Ignoring.");
			}
		} else if (auth != null) {
			LOG.warn("You have defined " + auth + " as auth type, but didn't provide any configuration, can't create authProvider");
		}

		instance.webroot = json.getString("webroot", "web/assets");
		instance.assetsPath = json.getString("static-path", "/assets");
		instance.tplDir = json.getString("views-dir", "web/views");
		instance.displayErrors = json.getBoolean("display-errors", Boolean.FALSE);
		// TODO : read sockJSOptions from config

		instance.globalHandlers.add(BodyHandler.create());
		return instance;
	}

	public ResourceBundle getResourceBundle(Locale loc) {
		return bundlesByLocale.get(loc);
	}

	public JsonObject json() {
		return json;
	}

	public List<String> getFixturePackages() {
		return fixturePackages;
	}

	public TypedParamInjectorRegistry getTypeInjectors() {
		return typeInjectors;
	}

	public AnnotatedParamInjectorRegistry getAnnotatedInjectors() {
		return annotInjectors;
	}

	public boolean isDisplayErrors() {
		return displayErrors;
	}

	public Map<String, TemplateEngine> getTemplateEngines() {
		return templateEngines;
	}

	public String getTplDir() {
		return tplDir;
	}

	public RateLimit getRateLimit() {
		return rateLimit;
	}

	public void setMarshallers(Map<String, PayloadMarshaller> marshallers) {
		this.marshallers = marshallers;
	}

	void createAnnotInjectors(ParameterAdapterRegistry registry) {
		annotInjectors = new AnnotatedParamInjectorRegistry(marshallers, registry);
	}

	public String getDomainPackage() {
		return domainPackage;
	}

	public ServiceRegistry getServiceRegistry() {
		return serviceRegistry;
	}

	void registerTemplateEngine(String extension, TemplateEngine engine) {
		templateEngines.put(extension, engine);
	}

	void setAuthProvider(AuthProvider authProvider) {
		this.authProvider = authProvider;
	}

	void setAuthMethod(AuthMethod authMethod) {
		this.authMethod = authMethod;
	}

	void registerInterceptor(String name, Handler<RoutingContext> handler) {
		aopHandlerRegistry.put(name, handler);
	}

	void registerService(String name, Object service) {
		serviceRegistry.registerService(name, service);
	}

	Object getService(String name) {
		return serviceRegistry.get(name);
	}

	void registerParamHandler(Class<?> parameterClass, Handler<RoutingContext> handler) {
		paramHandlers.put(parameterClass, handler);
	}

	public Set<Handler<RoutingContext>> getAnnotationHandler(Class<? extends Annotation> annotation) {
		return annotationHandlers.get(annotation);
	}

	void registerAnnotationHandler(Class<? extends Annotation> annotation, Set<Handler<RoutingContext>> handlers) {
		annotationHandlers.put(annotation, handlers);
	}

	void registerTypeProcessor(Class<?> type, Processor processor) {
		typeProcessors.put(type, processor);
	}

	<T extends Annotation> void registerAnnotationProcessor(Class<T> annotation, AnnotationProcessorFactory<T> processor) {
		apRegistry.registerProcessor(annotation, processor);
	}

	<T extends Annotation> void registerAnnotationProcessor(Class<T> annotation, AnnotationProcessor<T> processor) {
		apRegistry.registerProcessor(annotation, processor);
	}

	<T> void registerInjector(Class<? extends T> clazz, ParamInjector<T> injector) {
		typeInjectors.registerInjector(clazz, injector);
	}

	<T extends Annotation> void registerInjector(Class<? extends T> clazz, AnnotatedParamInjector<T> injector) {
		annotInjectors.registerInjector(clazz, injector);
	}

	void addHandler(Handler<RoutingContext> handler) {
		globalHandlers.add(handler);
	}

	String getWebroot() {
		return webroot;
	}

	String getAssetsPath() {
		return assetsPath;
	}

	public AuthProvider getAuthProvider() {
		return authProvider;
	}

	String getI18nDir() {
		return i18nDir;
	}

	void createBundle(Locale loc, ResourceBundle bundle) {
		bundlesByLocale.put(loc, bundle);
	}

	public String getVerticlePackage() {
		return verticlePackage;
	}

	public void forEachControllerPackage(Handler<? super String> consumer) {
		controllerPackages.forEach(consumer::handle);
	}

	public Vertx getVertx() {
		return vertx;
	}

	public SockJSHandlerOptions getSockJSOptions() {
		return sockJSOptions;
	}


	public Processor getTypeProcessor(Class<?> parameterClass) {
		return typeProcessors.get(parameterClass);
	}

	public Handler<RoutingContext> getParamHandler(Class<?> parameterClass) {
		return paramHandlers.get(parameterClass);
	}

	public AnnotationProcessor<?> getAnnotationProcessor(Annotation methodAnnotation) {
		return apRegistry.getProcessor(methodAnnotation);
	}

	public Handler<RoutingContext> getAopHandler(String name) {
		return aopHandlerRegistry.get(name);
	}

	public void forEachGlobalHandler(Handler<Handler<RoutingContext>> handler) {
		globalHandlers.forEach(handler::handle);
	}

	public Map<String, PayloadMarshaller> getMarshallers() {
		return marshallers;
	}

	public List<String> getControllerPackages() {
		return controllerPackages;
	}
}
