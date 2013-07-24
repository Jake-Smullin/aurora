package com.twitter.aurora.scheduler.thrift.aop;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.PrivateModule;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;

import org.aopalliance.intercept.MethodInterceptor;

import com.twitter.aurora.GuiceUtils;
import com.twitter.aurora.gen.MesosAdmin;
import com.twitter.aurora.gen.MesosSchedulerManager;
import com.twitter.aurora.scheduler.thrift.auth.CapabilityValidator;
import com.twitter.aurora.scheduler.thrift.auth.DecoratedThrift;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;

/**
 * Binding module for AOP-style decorations of the thrift API.
 */
public class AopModule extends AbstractModule {

  @CmdLine(name = "enable_job_updates", help = "Whether new job updates should be accepted.")
  private static final Arg<Boolean> ENABLE_UPDATES = Arg.create(true);

  @CmdLine(name = "enable_job_creation",
      help = "Allow new jobs to be created, if false all job creation requests will be denied.")
  private static final Arg<Boolean> ENABLE_JOB_CREATION = Arg.create(true);

  // TODO(wfarner): Consider implementing the equivalent of Matchers.and() so we can specify
  // the type here as well.
  private static final Matcher<? super Class<?>> THRIFT_IFACE_MATCHER =
      Matchers.annotatedWith(DecoratedThrift.class);

  private final Map<String, Boolean> toggledMethods;

  public AopModule() {
    this(ImmutableMap.of(
        "createJob", ENABLE_JOB_CREATION.get(),
        "startUpdate", ENABLE_UPDATES.get()));
  }

  @VisibleForTesting
  AopModule(Map<String, Boolean> toggledMethods) {
    this.toggledMethods = ImmutableMap.copyOf(toggledMethods);
  }

  private static final Function<Method, String> GET_NAME = new Function<Method, String>() {
    @Override public String apply(Method method) {
      return method.getName();
    }
  };

  @Override
  protected void configure() {
    requireBinding(CapabilityValidator.class);

    // Layer ordering:
    // Log -> CapabilityValidator -> FeatureToggle -> SchedulerThriftInterface

    bindThriftDecorator(new LoggingInterceptor());

    // Note: it's important that the capability interceptor is only applied to MesosAdmin.Iface
    // methods, and does not pick up methods on MesosSchedulerManager.Iface.
    MethodInterceptor authInterceptor = new UserCapabilityInterceptor();
    requestInjection(authInterceptor);
    bindInterceptor(
        THRIFT_IFACE_MATCHER,
        GuiceUtils.interfaceMatcher(MesosAdmin.Iface.class, true),
        authInterceptor);

    install(new PrivateModule() {
      @Override protected void configure() {
        // Ensure that the provided methods exist on the decorated interface.
        List<Method> methods = ImmutableList.copyOf(MesosSchedulerManager.Iface.class.getMethods());
        for (String toggledMethod : toggledMethods.keySet()) {
          Preconditions.checkArgument(
              Iterables.any(methods,
                  Predicates.compose(Predicates.equalTo(toggledMethod), GET_NAME)),
              String.format("Method %s was not found in class %s",
                  toggledMethod,
                  MesosSchedulerManager.Iface.class));
        }

        bind(new TypeLiteral<Map<String, Boolean>>() { }).toInstance(toggledMethods);
        bind(IsFeatureEnabled.class).in(Singleton.class);
        Key<Predicate<Method>> predicateKey = Key.get(new TypeLiteral<Predicate<Method>>() { });
        bind(predicateKey).to(IsFeatureEnabled.class);
        expose(predicateKey);
      }
    });
    bindThriftDecorator(new FeatureToggleInterceptor());
  }

  private void bindThriftDecorator(MethodInterceptor interceptor) {
    bindThriftDecorator(binder(), THRIFT_IFACE_MATCHER, interceptor);
  }

  @VisibleForTesting
  static void bindThriftDecorator(
      Binder binder,
      Matcher<? super Class<?>> classMatcher,
      MethodInterceptor interceptor) {

    binder.bindInterceptor(classMatcher, Matchers.any(), interceptor);
    binder.requestInjection(interceptor);
  }

  private static class IsFeatureEnabled implements Predicate<Method> {
    private final Predicate<String> methodEnabled;

    @Inject
    IsFeatureEnabled(Map<String, Boolean> toggleMethods) {
      Predicate<String> builder = Predicates.alwaysTrue();
      for (Map.Entry<String, Boolean> toggleMethod : toggleMethods.entrySet()) {
        Predicate<String> enableMethod = Predicates.or(
            toggleMethod.getValue()
                ? Predicates.<String>alwaysTrue()
                : Predicates.<String>alwaysFalse(),
            Predicates.not(Predicates.equalTo(toggleMethod.getKey())));
        builder = Predicates.and(builder, enableMethod);
      }
      methodEnabled = builder;
    }

    @Override
    public boolean apply(Method method) {
      return methodEnabled.apply(method.getName());
    }
  }
}
