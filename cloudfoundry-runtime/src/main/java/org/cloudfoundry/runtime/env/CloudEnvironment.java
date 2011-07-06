package org.cloudfoundry.runtime.env;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.jackson.map.ObjectMapper;

/**
 * Simpler access to Cloud Foundry environment.
 * <p>
 * This class interprets environment variables and provide a simple
 * access without needing JSON parsing.
 * </p>
 * 
 * @author Ramnivas Laddad
 *
 */
public class CloudEnvironment {

	private ObjectMapper objectMapper = new ObjectMapper();
	private EnvironmentAccessor environment = new EnvironmentAccessor();

	private static Map<Class<? extends AbstractServiceInfo>, Set<String>> serviceTypeToLabels = new HashMap<Class<? extends AbstractServiceInfo>, Set<String>>();

	private static void labelledServiceType(Class<? extends AbstractServiceInfo> serviceType,
				      String label)
	{
		Set<String> labels = serviceTypeToLabels.get(serviceType);
		if (labels == null) {
			labels = new HashSet<String>();
			serviceTypeToLabels.put(serviceType, labels);
		}

		labels.add(label);
	}

	static {
		labelledServiceType(MysqlServiceInfo.class, "mysql-5.1");
		labelledServiceType(RedisServiceInfo.class, "redis-2.2");
		labelledServiceType(MongoServiceInfo.class, "mongodb-1.8");
		labelledServiceType(PostgresqlServiceInfo.class, "postgresql-9.0");

                // the old rabbitmq service
		labelledServiceType(RabbitServiceInfo.class, "rabbitmq-2.4");
                // rabbitmq SRS-based service, during testing
		labelledServiceType(RabbitServiceInfo.class, "rabbitmq-srs-2.4.1");
                // rabbitmq SRS-based service, after the old service is gone
		labelledServiceType(RabbitServiceInfo.class, "rabbitmq-2.4.1");
	}
	
	/* package for testing purpose */
	void setCloudEnvironment(EnvironmentAccessor environment) {
		this.environment = environment;
	}
	
	public String getValue(String key) {
		return environment.getValue(key);
	}
	
	@SuppressWarnings("unchecked")
	public ApplicationInstanceInfo getInstanceInfo() {
		String instanceInfoString = getValue("VCAP_APPLICATION");
		if (instanceInfoString == null || instanceInfoString.trim().isEmpty()) {
			return null;
		}
		try {
			Map<String,Object> infoMap = objectMapper.readValue(instanceInfoString, Map.class);
			return new ApplicationInstanceInfo(infoMap);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} 
	}
	
	public String getCloudApiUri() {
		ApplicationInstanceInfo instanceInfo = getInstanceInfo();
		if (instanceInfo == null) {
			throw new IllegalArgumentException("There is no cloud API urls in a non-cloud deployment");
		}
		List<String> uris = instanceInfo.getUris();
		String defaultUri = uris.get(0);
		return "api" + defaultUri.substring(defaultUri.indexOf("."));
	}
	
	/**
	 * Return object representation of the VCAP_SERIVCES environment variable
	 * <p>
	 * Returns a map whose key is the label (for example "redis-2.2") of the 
	 * service and value is a list of services for that label. Each list element
	 * is a map with service attributes. 
	 * </p>
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Map<String, List<Map<String,Object>>> getRawServices() {
		String servicesString = getValue("VCAP_SERVICES");
		if (servicesString == null || servicesString.length() == 0) {
			return new HashMap<String, List<Map<String,Object>>>();
		}
		try {
			return objectMapper.readValue(servicesString, Map.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		} 
	}
	
	public List<Map<String,Object>> getServices() {
		Map<String, List<Map<String,Object>>> rawServices = getRawServices();
		
		List<Map<String,Object>> flatServices = new ArrayList<Map<String,Object>>();
		for (Map.Entry<String, List<Map<String,Object>>> entry : rawServices.entrySet()) {
			flatServices.addAll(entry.getValue());
		}
		return flatServices;
	}
	
	private Map<String, Object> getServiceDataByName(String name) {
		List<Map<String, Object>> services = getServices();
		
		for (Map<String, Object> service : services) {
			if (service.get("name").equals(name)) {
				return service;
			}
		}
		return null;
	}

	private List<Map<String, Object>> getServiceDataByLabels(Set<String> labels) {
		List<Map<String, Object>> services = getServices();
		List<Map<String, Object>> matchedServices = new ArrayList<Map<String,Object>>();
		for (Map<String, Object> service : services) {
		    if (labels.contains(service.get("label"))) {
			matchedServices.add(service);
		    }
		}

		return matchedServices;
	}

	public <T extends AbstractServiceInfo> T getServiceInfo(String name, Class<T> serviceInfoType) {
		Map<String,Object> serviceInfoMap = getServiceDataByName(name);
		Set<String> labels = serviceTypeToLabels.get(serviceInfoType);

		if (labels != null && labels.contains(serviceInfoMap.get("label"))) {
		    return getServiceInfo(serviceInfoMap, serviceInfoType);
		} else {
		    return null;
		}
	}

	public <T extends AbstractServiceInfo> List<T> getServiceInfos(Class<T> serviceInfoType) {
		Set<String> labels = serviceTypeToLabels.get(serviceInfoType);
		List<T> serviceInfos = new ArrayList<T>();

		if (labels != null) {
			List<Map<String,Object>> serviceInfoMaps = getServiceDataByLabels(labels);

			for (Map<String,Object> serviceInfoMap : serviceInfoMaps)
				serviceInfos.add(getServiceInfo(serviceInfoMap, serviceInfoType));
		}

		return serviceInfos;
	}

	private <T extends AbstractServiceInfo> T getServiceInfo(Map<String,Object> serviceInfoMap, Class<T> serviceInfoType) {
		try {
			Constructor<T> ctor = serviceInfoType.getConstructor(Map.class);
			return ctor.newInstance(serviceInfoMap);
		} catch (Exception e) {
			throw new CloudServiceException("Failed to create service information for " + serviceInfoMap.get("name"), e);
		}
	}
	
	/**
	 * Environment available to the deployed app.
	 * 
	 * The main purpose of this class is to allow unit-testing of {@link CloudEnvironment}
	 *
	 */
	public static class EnvironmentAccessor {
		public String getValue(String key) {
			return System.getenv(key);
		}
	}
}
