/*
 * Copyright 2004-2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.webflow.config;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.binding.convert.ConversionExecutor;
import org.springframework.core.io.Resource;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.webflow.core.collection.AttributeMap;
import org.springframework.webflow.core.collection.LocalAttributeMap;
import org.springframework.webflow.core.collection.MutableAttributeMap;
import org.springframework.webflow.definition.FlowDefinition;
import org.springframework.webflow.definition.registry.FlowDefinitionConstructionException;
import org.springframework.webflow.definition.registry.FlowDefinitionHolder;
import org.springframework.webflow.definition.registry.FlowDefinitionRegistry;
import org.springframework.webflow.engine.builder.DefaultFlowHolder;
import org.springframework.webflow.engine.builder.FlowAssembler;
import org.springframework.webflow.engine.builder.FlowBuilder;
import org.springframework.webflow.engine.builder.FlowBuilderContext;
import org.springframework.webflow.engine.builder.model.FlowModelFlowBuilder;
import org.springframework.webflow.engine.builder.support.FlowBuilderContextImpl;
import org.springframework.webflow.engine.builder.support.FlowBuilderServices;
import org.springframework.webflow.engine.model.builder.DefaultFlowModelHolder;
import org.springframework.webflow.engine.model.builder.FlowModelBuilder;
import org.springframework.webflow.engine.model.builder.ResourceBackedFlowModelBuilder;
import org.springframework.webflow.engine.model.registry.FlowModelHolder;

/**
 * A factory for a flow definition registry. Is a Spring FactoryBean, for provision by the flow definition registry bean
 * definition parser. Is package-private, as people should not be using this class directly, but rather through the
 * higher-level webflow-config Spring 2.x configuration namespace.
 * 
 * @author Keith Donald
 * @author Jeremy Grelle
 * @author Scott Andrews
 */
class FlowRegistryFactoryBean implements FactoryBean<FlowDefinitionRegistry>, BeanClassLoaderAware, InitializingBean,
		DisposableBean {

	private FlowLocation[] flowLocations;

	private String[] flowLocationPatterns;

	private FlowBuilderInfo[] flowBuilders;

	private FlowBuilderServices flowBuilderServices;

	private FlowDefinitionRegistry parent;

	private String basePath;

	private ClassLoader classLoader;

	private Map<String, String> flowModelBuilderMap = new HashMap<String, String>(1);

	/**
	 * The definition registry produced by this factory bean.
	 */
	private DefaultFlowRegistry flowRegistry;

	/**
	 * A helper for creating abstract representation of externalized flow definition resources.
	 */
	private FlowDefinitionResourceFactory flowResourceFactory;

	/**
	 * Flow definitions defined in external files that should be registered in the registry produced by this factory
	 * bean.
	 */
	public void setFlowLocations(FlowLocation[] flowLocations) {
		this.flowLocations = flowLocations;
	}

	/**
	 * Resolvable path patterns to flows to register in the registry produced by this factory bean.
	 */
	public void setFlowLocationPatterns(String[] flowLocationPatterns) {
		this.flowLocationPatterns = flowLocationPatterns;
	}

	/**
	 * Java {@link FlowBuilder flow builder} classes that should be registered in the registry produced by this factory
	 * bean.
	 */
	public void setFlowBuilders(FlowBuilderInfo[] flowBuilders) {
		this.flowBuilders = flowBuilders;
	}

	/**
	 * Java model builder classes that should be registered within this factory bean.
	 */
	public void setFlowModelBuilders(FlowModelBuilderInfo[] flowModelBuilders) {
		for (FlowModelBuilderInfo info : flowModelBuilders) {
			flowModelBuilderMap.put(info.getExtension(), info.getClassName());
		}
	}

	/**
	 * The holder for services needed to build flow definitions registered in this registry.
	 */
	public void setFlowBuilderServices(FlowBuilderServices flowBuilderServices) {
		this.flowBuilderServices = flowBuilderServices;
	}

	/**
	 * Base path used when determining the default flow id
	 */
	public void setBasePath(String basePath) {
		this.basePath = basePath;
	}

	/**
	 * The parent of the registry created by this factory bean.
	 */
	public void setParent(FlowDefinitionRegistry parent) {
		this.parent = parent;
	}

	// implement BeanClassLoaderAware

	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	public void afterPropertiesSet() throws Exception {
		flowResourceFactory = new FlowDefinitionResourceFactory(flowBuilderServices.getApplicationContext());
		if (basePath != null) {
			flowResourceFactory.setBasePath(basePath);
		}
		flowRegistry = new DefaultFlowRegistry();
		flowRegistry.setParent(parent);
		if (!flowModelBuilderMap.containsKey("xml")) {
			/* Default, override-able registration of '.xml' flow model builder */
			flowModelBuilderMap.put("xml", "org.springframework.webflow.engine.model.builder.xml.XmlFlowModelBuilder");
		}
		registerFlowLocations();
		registerFlowLocationPatterns();
		registerFlowBuilders();
	}

	public FlowDefinitionRegistry getObject() throws Exception {
		return flowRegistry;
	}

	public Class<?> getObjectType() {
		return FlowDefinitionRegistry.class;
	}

	public boolean isSingleton() {
		return true;
	}

	// implement DisposableBean

	public void destroy() throws Exception {
		flowRegistry.destroy();
	}

	private void registerFlowLocations() {
		if (flowLocations != null) {
			for (FlowLocation location : flowLocations) {
				flowRegistry.registerFlowDefinition(createFlowDefinitionHolder(createResource(location)));
			}
		}
	}

	private void registerFlowLocationPatterns() {
		if (flowLocationPatterns != null) {
			for (String pattern : flowLocationPatterns) {
				FlowDefinitionResource[] resources;
				AttributeMap<Object> attributes = getFlowAttributes(Collections.<FlowElementAttribute> emptySet());
				try {
					resources = flowResourceFactory.createResources(pattern, attributes);
				} catch (IOException e) {
					IllegalStateException ise = new IllegalStateException(
							"An I/O Exception occurred resolving the flow location pattern '" + pattern + "'");
					ise.initCause(e);
					throw ise;
				}
				for (FlowDefinitionResource resource : resources) {
					flowRegistry.registerFlowDefinition(createFlowDefinitionHolder(resource));
				}
			}
		}
	}

	private void registerFlowBuilders() {
		if (flowBuilders != null) {
			for (FlowBuilderInfo builderInfo : flowBuilders) {
				flowRegistry.registerFlowDefinition(buildFlowDefinition(builderInfo));
			}
		}
	}

	private FlowDefinitionHolder createFlowDefinitionHolder(FlowDefinitionResource flowResource) {
		FlowBuilder builder = createFlowBuilder(flowResource);
		FlowBuilderContext builderContext = new FlowBuilderContextImpl(flowResource.getId(),
				flowResource.getAttributes(), flowRegistry, flowBuilderServices);
		FlowAssembler assembler = new FlowAssembler(builder, builderContext);
		return new DefaultFlowHolder(assembler);
	}

	private FlowDefinitionResource createResource(FlowLocation location) {
		AttributeMap<Object> flowAttributes = getFlowAttributes(location.getAttributes());
		return flowResourceFactory.createResource(location.getPath(), flowAttributes, location.getId());
	}

	private AttributeMap<Object> getFlowAttributes(Set<FlowElementAttribute> attributes) {
		MutableAttributeMap<Object> flowAttributes = null;
		if (flowBuilderServices.getDevelopment()) {
			flowAttributes = new LocalAttributeMap<Object>(1 + attributes.size(), 1);
			flowAttributes.put("development", Boolean.TRUE);
		}
		if (!attributes.isEmpty()) {
			if (flowAttributes == null) {
				flowAttributes = new LocalAttributeMap<Object>(attributes.size(), 1);
			}
			for (FlowElementAttribute attribute : attributes) {
				flowAttributes.put(attribute.getName(), getConvertedValue(attribute));
			}
		}
		return flowAttributes;
	}

	private FlowBuilder createFlowBuilder(FlowDefinitionResource resource) {
		return new FlowModelFlowBuilder(createFlowModelHolder(resource));
	}

	private FlowModelHolder createFlowModelHolder(FlowDefinitionResource resource) {
		FlowModelHolder modelHolder = new DefaultFlowModelHolder(createFlowModelBuilder(resource));
		// register the flow model holder with the backing flow model registry - this is needed to support flow model
		// merging during the flow build process
		flowRegistry.getFlowModelRegistry().registerFlowModel(resource.getId(), modelHolder);
		return modelHolder;
	}

	private FlowModelBuilder createFlowModelBuilder(FlowDefinitionResource resource) {
		String extension = getExtension(resource.getPath());
		if (!flowModelBuilderMap.containsKey(extension)) {
			throw new IllegalArgumentException(resource.getPath().getFilename()
					+ " is not a supported resource type; supported types are " + flowModelBuilderMap.keySet());
		}
		String builderClassName = flowModelBuilderMap.get(extension);
		Class<?> flowModelBuilderClass = loadClass(builderClassName);
		ResourceBackedFlowModelBuilder builder;
		try {
			builder = (ResourceBackedFlowModelBuilder) flowModelBuilderClass.newInstance();
		} catch (InstantiationException e) {
			throw new FlowDefinitionConstructionException(builderClassName, e);
		} catch (IllegalAccessException e) {
			throw new FlowDefinitionConstructionException(builderClassName, e);
		}
		builder.setFlowModelLocator(flowRegistry.getFlowModelRegistry());
		builder.setFlowResource(resource.getPath());
		return builder;
	}

	private String getExtension(Resource path) {
		String ext = StringUtils.getFilenameExtension(path.getFilename());
		if (ext == null) {
			throw new IllegalArgumentException("Flow path [" + path + "] requires an extension.");
		}
		return ext;
	}

	private Object getConvertedValue(FlowElementAttribute attribute) {
		if (attribute.needsTypeConversion()) {
			Class<?> targetType = fromStringToClass(attribute.getType());
			ConversionExecutor converter = flowBuilderServices.getConversionService().getConversionExecutor(
					String.class, targetType);
			return converter.execute(attribute.getValue());
		} else {
			return attribute.getValue();
		}
	}

	private Class<?> fromStringToClass(String name) {
		Class<?> clazz = flowBuilderServices.getConversionService().getClassForAlias(name);
		if (clazz != null) {
			return clazz;
		} else {
			return loadClass(name);
		}
	}

	private Class<?> loadClass(String name) {
		try {
			return ClassUtils.forName(name, classLoader);
		} catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("Unable to load class '" + name + "'");
		}
	}

	private FlowDefinition buildFlowDefinition(FlowBuilderInfo builderInfo) {
		try {
			Class<?> flowBuilderClass = loadClass(builderInfo.getClassName());
			FlowBuilder builder = (FlowBuilder) flowBuilderClass.newInstance();
			AttributeMap<Object> flowAttributes = getFlowAttributes(builderInfo.getAttributes());
			FlowBuilderContext builderContext = new FlowBuilderContextImpl(builderInfo.getId(), flowAttributes,
					flowRegistry, flowBuilderServices);
			FlowAssembler assembler = new FlowAssembler(builder, builderContext);
			return assembler.assembleFlow();
		} catch (IllegalArgumentException e) {
			throw new FlowDefinitionConstructionException(builderInfo.getId(), e);
		} catch (InstantiationException e) {
			throw new FlowDefinitionConstructionException(builderInfo.getId(), e);
		} catch (IllegalAccessException e) {
			throw new FlowDefinitionConstructionException(builderInfo.getId(), e);
		}
	}

}