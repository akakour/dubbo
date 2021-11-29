/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config.spring.beans.factory.annotation;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.config.spring.context.event.ServiceBeanExportedEvent;
import com.alibaba.dubbo.config.spring.util.AnnotationUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that Consumer service {@link Reference} annotated fields
 *
 * @since 2.5.7
 */
public class ReferenceAnnotationBeanPostProcessor extends AnnotationInjectedBeanPostProcessor<Reference>
        implements ApplicationContextAware, ApplicationListener {

    /**
     * The bean name of {@link ReferenceAnnotationBeanPostProcessor}
     */
    public static final String BEAN_NAME = "referenceAnnotationBeanPostProcessor";

    /**
     * Cache size
     */
    private static final int CACHE_SIZE = Integer.getInteger(BEAN_NAME + ".cache.size", 32);

    private final ConcurrentMap<String, ReferenceBean<?>> referenceBeanCache =
            new ConcurrentHashMap<String, ReferenceBean<?>>(CACHE_SIZE);

    private final ConcurrentHashMap<String, ReferenceBeanInvocationHandler> localReferenceBeanInvocationHandlerCache =
            new ConcurrentHashMap<String, ReferenceBeanInvocationHandler>(CACHE_SIZE);

    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedFieldReferenceBeanCache =
            new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    private final ConcurrentMap<InjectionMetadata.InjectedElement, ReferenceBean<?>> injectedMethodReferenceBeanCache =
            new ConcurrentHashMap<InjectionMetadata.InjectedElement, ReferenceBean<?>>(CACHE_SIZE);

    private ApplicationContext applicationContext;

    /**
     * Gets all beans of {@link ReferenceBean}
     *
     * @return non-null read-only {@link Collection}
     * @since 2.5.9
     */
    public Collection<ReferenceBean<?>> getReferenceBeans() {
        return referenceBeanCache.values();
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected field.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedFieldReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedFieldReferenceBeanCache);
    }

    /**
     * Get {@link ReferenceBean} {@link Map} in injected method.
     *
     * @return non-null {@link Map}
     * @since 2.5.11
     */
    public Map<InjectionMetadata.InjectedElement, ReferenceBean<?>> getInjectedMethodReferenceBeanMap() {
        return Collections.unmodifiableMap(injectedMethodReferenceBeanCache);
    }

    /**
     * 得到 @Refernece 注入类代理实例
     * @param reference
     * @param bean
     * @param beanName
     * @param injectedType
     * @param injectedElement
     * @return
     * @throws Exception
     */
    @Override
    protected Object doGetInjectedBean(Reference reference, Object bean, String beanName, Class<?> injectedType,
                                       InjectionMetadata.InjectedElement injectedElement) throws Exception {

        // 创建beanname
        String referencedBeanName = buildReferencedBeanName(reference, injectedType);

        // 将@Reference注入对象封装成RefernceBean  注意的是referencebean并没有放入spring容器
        ReferenceBean referenceBean = buildReferenceBeanIfAbsent(referencedBeanName, reference, injectedType, getClassLoader());

        // 缓存
        cacheInjectedReferenceBean(referenceBean, injectedElement);

        /**
         * 核心 生成远程RPC调用代理类 是个JDK动态代理
         * 所以，当调用引用类的方法时，
         *     会调用到ReferenceBeanInvocationHandler的invoke方法，进而调用到referenceBean的get方法等
         */
        Object proxy = buildProxy(referencedBeanName, referenceBean, injectedType);

        return proxy;
    }

    /**
     * 生成rpc动态代理  典型的jdk动态代理
     * @param referencedBeanName
     * @param referenceBean
     * @param injectedType
     * @return
     */
    private Object buildProxy(String referencedBeanName, ReferenceBean referenceBean, Class<?> injectedType) {
        // handler
        InvocationHandler handler = buildInvocationHandler(referencedBeanName, referenceBean);
        Object proxy = Proxy.newProxyInstance(getClassLoader(), new Class[]{injectedType}, handler);
        return proxy;
    }

    /**
     * 创建  reference 的jdk代理的invocationhandler
     * @param referencedBeanName
     * @param referenceBean
     * @return
     */
    private InvocationHandler buildInvocationHandler(String referencedBeanName, ReferenceBean referenceBean) {

        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.get(referencedBeanName);

        if (handler == null) {
            /**
             * @Reference 注入的是一个ReferenceBeanInvocationHandler的jdk代理对象。关键直接看ReferenceBeanInvocationHandler.invoke
             */
            handler = new ReferenceBeanInvocationHandler(referenceBean);
        }

        /**
         * 理论上
         * 1. XML形式的dubbo的reference 的bean，既可以@Autowried也可以@Reference注入。这个是因为xml形式bean会被解析成BD，从而被实例化，再被DI引用，所以会存在Spring上下文
         * 2. 注解版的dubbo，只能@Reference注入dubbo引用，不能@Autowried,因为，reference bean不会存在spring上下文中，会直接getObject得到代理类。
         */
        // xml形式的dubbo，有可能是在上下文中
        if (applicationContext.containsBean(referencedBeanName)) { // Is local @Service Bean or not ?
            // ReferenceBeanInvocationHandler's initialization has to wait for current local @Service Bean has been exported.
            localReferenceBeanInvocationHandlerCache.put(referencedBeanName, handler);
        } else {
            /**
             * 基本都是这里
             * 远程引用 Bean 应立即初始化
             */
            handler.init();
        }

        return handler;
    }

    private static class ReferenceBeanInvocationHandler implements InvocationHandler {

        private final ReferenceBean referenceBean;

        private Object bean;

        private ReferenceBeanInvocationHandler(ReferenceBean referenceBean) {
            this.referenceBean = referenceBean;
        }

        /**
         * reference dubbo服务调用时的jdk代理invoke
         * @param proxy
         * @param method
         * @param args
         * @return
         * @throws Throwable
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = null;
            try {
                if (bean == null) { // If the bean is not initialized, invoke init()
                    // issue: https://github.com/apache/incubator-dubbo/issues/3429
                    init();
                }
                result = method.invoke(bean, args);
            } catch (InvocationTargetException e) {
                // re-throws the actual Exception.
                throw e.getTargetException();
            }
            return result;
        }

        /**
         * ref 引用bean的初始化。基本是建立rpc连接，创建调用链等
         */
        private void init() {
            this.bean = referenceBean.get();
        }
    }

    @Override
    protected String buildInjectedObjectCacheKey(Reference reference, Object bean, String beanName,
                                                 Class<?> injectedType, InjectionMetadata.InjectedElement injectedElement) {

        String key = buildReferencedBeanName(reference, injectedType) +
                "#source=" + (injectedElement.getMember()) +
                "#attributes=" + AnnotationUtils.getAttributes(reference, getEnvironment(), true);

        return key;
    }

    private String buildReferencedBeanName(Reference reference, Class<?> injectedType) {

        ServiceBeanNameBuilder builder = ServiceBeanNameBuilder.create(reference, injectedType, getEnvironment());

        return getEnvironment().resolvePlaceholders(builder.build());
    }

    private ReferenceBean buildReferenceBeanIfAbsent(String referencedBeanName, Reference reference,
                                                     Class<?> referencedType, ClassLoader classLoader)
            throws Exception {

        ReferenceBean<?> referenceBean = referenceBeanCache.get(referencedBeanName);

        if (referenceBean == null) {
            ReferenceBeanBuilder beanBuilder = ReferenceBeanBuilder
                    .create(reference, classLoader, applicationContext)
                    .interfaceClass(referencedType);
            referenceBean = beanBuilder.build();
            referenceBeanCache.put(referencedBeanName, referenceBean);
        }

        return referenceBean;
    }

    /**
     * 全局缓存@Reference bean
     * @param referenceBean
     * @param injectedElement
     */
    private void cacheInjectedReferenceBean(ReferenceBean referenceBean,
                                            InjectionMetadata.InjectedElement injectedElement) {
        if (injectedElement.getMember() instanceof Field) {
            injectedFieldReferenceBeanCache.put(injectedElement, referenceBean);
        } else if (injectedElement.getMember() instanceof Method) {
            injectedMethodReferenceBeanCache.put(injectedElement, referenceBean);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * @Reference 的事件监听处理
     * @param event
     */
    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        // 1. @ServiceBean暴露成功之后的处理
        if (event instanceof ServiceBeanExportedEvent) {
            // 这里才会真正的生成ReferenceBean的代理对象
            onServiceBeanExportEvent((ServiceBeanExportedEvent) event);
        } else if (event instanceof ContextRefreshedEvent) {
            // 2. spring容器初始化成功之后的处理，实际是一个扩张接口，并没有实际处理
            onContextRefreshedEvent((ContextRefreshedEvent) event);
        }
    }

    private void onServiceBeanExportEvent(ServiceBeanExportedEvent event) {
        ServiceBean serviceBean = event.getServiceBean();
        initReferenceBeanInvocationHandler(serviceBean);
    }

    private void initReferenceBeanInvocationHandler(ServiceBean serviceBean) {
        String serviceBeanName = serviceBean.getBeanName();
        // Remove ServiceBean when it's exported
        ReferenceBeanInvocationHandler handler = localReferenceBeanInvocationHandlerCache.remove(serviceBeanName);
        // Initialize
        if (handler != null) {
            handler.init();
        }
    }

    private void onContextRefreshedEvent(ContextRefreshedEvent event) {

    }


    @Override
    public void destroy() throws Exception {
        super.destroy();
        this.referenceBeanCache.clear();
        this.localReferenceBeanInvocationHandlerCache.clear();
        this.injectedFieldReferenceBeanCache.clear();
        this.injectedMethodReferenceBeanCache.clear();
    }
}
