/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.routing;

import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.setFlowConstructIfNeeded;
import static org.mule.runtime.core.api.lifecycle.LifecycleUtils.setMuleContextIfNeeded;
import static org.mule.runtime.core.api.processor.MessageProcessors.newChain;
import static org.mule.runtime.core.api.processor.MessageProcessors.newExplicitChain;
import org.mule.runtime.core.api.Event;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.core.api.config.ThreadingProfile;
import org.mule.runtime.core.api.context.MuleContextAware;
import org.mule.runtime.core.api.exception.MessagingExceptionHandlerAware;
import org.mule.runtime.api.lifecycle.Initialisable;
import org.mule.runtime.api.lifecycle.InitialisationException;
import org.mule.runtime.api.lifecycle.Startable;
import org.mule.runtime.api.lifecycle.Stoppable;
import org.mule.runtime.core.api.processor.MessageProcessorChain;
import org.mule.runtime.core.api.processor.Processor;
import org.mule.runtime.core.api.store.ListableObjectStore;
import org.mule.runtime.api.i18n.I18nMessageFactory;
import org.mule.runtime.core.routing.filters.ExpressionFilter;
import org.mule.runtime.core.routing.outbound.AbstractOutboundRouter;
import org.mule.runtime.api.util.Preconditions;
import org.mule.runtime.core.util.concurrent.NamedThreadFactory;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * UntilSuccessful attempts to route a message to the message processor it contains. Routing is considered successful if no
 * exception has been raised and, optionally, if the response matches an expression.
 *
 * UntilSuccessful internal route can be executed synchronously or asynchronously depending on the threading profile defined on
 * it. By default, if no threading profile is defined, then it will use the default threading profile configuration for the
 * application. This means that the default behavior is to process asynchronously.
 *
 * UntilSuccessful can optionally be configured to synchronously return an acknowledgment message when it has scheduled the event
 * for processing. UntilSuccessful is backed by a {@link ListableObjectStore} for storing the events that are pending
 * (re)processing.
 *
 * To execute until-successful asynchronously the threading profile defined on it must have doThreading attribute set with true
 * value.
 *
 * To execute until-successful synchronously the threading profile defined on it must have doThreading attribute set with false
 * value.
 */
public class UntilSuccessful extends AbstractOutboundRouter implements UntilSuccessfulConfiguration {

  public static final String PROCESS_ATTEMPT_COUNT_PROPERTY_NAME = "process.attempt.count";
  static final int DEFAULT_PROCESS_ATTEMPT_COUNT_PROPERTY_VALUE = 1;
  private static final long DEFAULT_MILLIS_BETWEEN_RETRIES = 60 * 1000;

  private ListableObjectStore<Event> objectStore;
  private int maxRetries = 5;
  private Long millisBetweenRetries = null;
  private Long secondsBetweenRetries = null;
  private String failureExpression;
  private String ackExpression;
  private ExpressionFilter failureExpressionFilter;
  private String eventKeyPrefix;
  protected Object deadLetterQueue;
  protected Processor dlqMP;
  private boolean synchronous = false;
  private ThreadingProfile threadingProfile;
  private UntilSuccessfulProcessingStrategy untilSuccessfulStrategy;

  @Override
  public void initialise() throws InitialisationException {
    if (routes.isEmpty()) {
      throw new InitialisationException(I18nMessageFactory
          .createStaticMessage("One message processor must be configured within UntilSuccessful."), this);
    }

    if (routes.size() > 1) {
      throw new InitialisationException(I18nMessageFactory
          .createStaticMessage("Only one message processor is allowed within UntilSuccessful."
              + " Use a Processor Chain to group several message processors into one."), this);
    }

    setWaitTime();

    super.initialise();

    if (deadLetterQueue != null) {
      resolveDlqMessageProcessor();
    }

    if (failureExpression != null) {
      failureExpressionFilter = new ExpressionFilter(failureExpression);
    } else {
      failureExpressionFilter = new ExpressionFilter("exception != null");
    }
    failureExpressionFilter.setMuleContext(muleContext);

    if ((ackExpression != null) && (!muleContext.getExpressionManager().isExpression(ackExpression))) {
      throw new InitialisationException(I18nMessageFactory.createStaticMessage("Invalid ackExpression: " + ackExpression), this);
    }

    if (synchronous) {
      this.untilSuccessfulStrategy = new SynchronousUntilSuccessfulProcessingStrategy();
    } else {
      if (threadingProfile == null) {
        threadingProfile = muleContext.getDefaultThreadingProfile();
      }
      this.untilSuccessfulStrategy = new AsynchronousUntilSuccessfulProcessingStrategy();
      ((MessagingExceptionHandlerAware) this.untilSuccessfulStrategy).setMessagingExceptionHandler(messagingExceptionHandler);
    }
    this.untilSuccessfulStrategy.setUntilSuccessfulConfiguration(this);

    if (untilSuccessfulStrategy instanceof Initialisable) {
      ((Initialisable) untilSuccessfulStrategy).initialise();
    }
    if (untilSuccessfulStrategy instanceof MuleContextAware) {
      ((MuleContextAware) untilSuccessfulStrategy).setMuleContext(muleContext);
    }
    String flowName = flowConstruct.getName();
    String clusterId = muleContext.getClusterId();
    eventKeyPrefix = flowName + "-" + clusterId + "-";
  }

  protected void resolveDlqMessageProcessor() throws InitialisationException {
    if (deadLetterQueue instanceof Processor) {
      dlqMP = (Processor) deadLetterQueue;
    } else {
      throw new InitialisationException(I18nMessageFactory
          .createStaticMessage("deadLetterQueue-ref is not a valid mesage processor: " + deadLetterQueue), null, this);
    }
  }

  private void setWaitTime() {
    boolean hasSeconds = secondsBetweenRetries != null;
    boolean hasMillis = millisBetweenRetries != null;

    Preconditions
        .checkArgument(!(hasSeconds && hasMillis),
                       "Can't specify millisBetweenRetries and secondsBetweenRetries properties at the same time. Please specify only one and remember that secondsBetweenRetries is deprecated.");

    if (hasSeconds) {
      logger
          .warn("You're using the secondsBetweenRetries in the until-successful router. That attribute was deprecated in favor of the new millisBetweenRetries."
              + "Please consider updating your config since the old attribute will be removed in Mule 4");

      setMillisBetweenRetries(TimeUnit.SECONDS.toMillis(secondsBetweenRetries));
    } else if (!hasMillis) {
      millisBetweenRetries = DEFAULT_MILLIS_BETWEEN_RETRIES;
    }
  }

  @Override
  public void start() throws MuleException {
    super.start();
    if (untilSuccessfulStrategy instanceof Startable) {
      ((Startable) untilSuccessfulStrategy).start();
    }
  }

  @Override
  public ScheduledThreadPoolExecutor createScheduledRetriesPool(final String threadPrefix) {
    return new ScheduledThreadPoolExecutor(1, new NamedThreadFactory(threadPrefix + "_retries",
                                                                     Thread.currentThread().getContextClassLoader()));
  }

  @Override
  public void stop() throws MuleException {
    if (untilSuccessfulStrategy instanceof Stoppable) {
      ((Stoppable) untilSuccessfulStrategy).stop();
    }
    super.stop();
  }

  @Override
  public boolean isMatch(final Event event, Event.Builder builder) throws MuleException {
    return true;
  }

  @Override
  protected Event route(final Event event) throws MuleException {
    return untilSuccessfulStrategy.route(event, flowConstruct);
  }

  @Override
  public ListableObjectStore<Event> getObjectStore() {
    return objectStore;
  }

  public void setObjectStore(final ListableObjectStore<Event> objectStore) {
    this.objectStore = objectStore;
  }

  @Override
  public int getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(final int maxRetries) {
    this.maxRetries = maxRetries;
  }

  /**
   * @deprecated use {@link #setMillisBetweenRetries(long)} instead
   * @param secondsBetweenRetries the number of seconds to wait between retries
   */
  @Deprecated
  public void setSecondsBetweenRetries(final long secondsBetweenRetries) {
    this.secondsBetweenRetries = secondsBetweenRetries;
  }

  @Override
  public long getMillisBetweenRetries() {
    return millisBetweenRetries;
  }

  public void setMillisBetweenRetries(long millisBetweenRetries) {
    this.millisBetweenRetries = millisBetweenRetries;
  }

  public String getFailureExpression() {
    return failureExpression;
  }

  public void setFailureExpression(final String failureExpression) {
    this.failureExpression = failureExpression;
  }

  @Override
  public String getAckExpression() {
    return ackExpression;
  }

  public void setAckExpression(final String ackExpression) {
    this.ackExpression = ackExpression;
  }

  public void setDeadLetterQueue(final Object deadLetterQueue) {
    this.deadLetterQueue = deadLetterQueue;
  }

  public Object getDeadLetterQueue() {
    return deadLetterQueue;
  }

  public String getEventKeyPrefix() {
    return eventKeyPrefix;
  }

  @Override
  public ExpressionFilter getFailureExpressionFilter() {
    return failureExpressionFilter;
  }

  public void setThreadingProfile(ThreadingProfile threadingProfile) {
    this.threadingProfile = threadingProfile;
  }

  @Override
  public ThreadingProfile getThreadingProfile() {
    return threadingProfile;
  }

  @Override
  public Processor getDlqMP() {
    return dlqMP;
  }

  @Override
  public Processor getRoute() {
    final MessageProcessorChain chain = newChain(newExplicitChain(routes.get(0)));
    chain.setMuleContext(muleContext);
    chain.setFlowConstruct(flowConstruct);
    return chain;
  }

  @Override
  public AbstractOutboundRouter getRouter() {
    return this;
  }

  public void setSynchronous(boolean synchronous) {
    this.synchronous = synchronous;
  }

}

