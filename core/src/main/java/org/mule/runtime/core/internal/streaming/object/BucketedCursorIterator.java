/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.streaming.object;

import org.mule.runtime.api.streaming.objects.CursorIteratorProvider;
import org.mule.runtime.core.internal.streaming.AbstractCursorIterator;
import org.mule.runtime.core.internal.streaming.object.iterator.ConsumerIterator;
import org.mule.runtime.core.streaming.objects.InMemoryCursorIteratorConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class BucketedCursorIterator<T> extends AbstractCursorIterator<T> {

  private final ConsumerIterator<T> stream;
  private final InMemoryCursorIteratorConfig config;
  private List<Bucket<T>> buckets;

  public BucketedCursorIterator(ConsumerIterator<T> stream, InMemoryCursorIteratorConfig config, CursorIteratorProvider provider) {
    super(provider);
    this.stream = stream;
    this.config = config;
    initialiseBuckets();
  }

  private void initialiseBuckets() {
    // TODO: try to calculate max amount of buckets?
    buckets = new ArrayList<>();
    buckets.add(new Bucket<>(config.getInitialBufferSize()));
  }

  @Override
  public boolean hasNext() {
    return stream.hasNext();
  }

  @Override
  public T next() {
    return null;
  }

  @Override
  public void release() {
    //TODO
  }

  @Override
  public void close() throws IOException {
    //TODO
  }

  @Override
  public void forEachRemaining(Consumer<? super T> action) {
    //TODO 
  }

  private class Bucket<T> {

    private final List<T> items;
    private final int capacity;

    private Bucket(int capacity) {
      this.capacity = capacity;
      this.items = new ArrayList<>(capacity);
    }

    private boolean add(T item) {
      synchronized (items) {
        if (items.size() < capacity) {
          items.add(item);
          return true;
        }
      }

      return false;
    }

    private T get(int index) {
      return items.get(index);
    }
  }

}
