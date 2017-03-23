/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.streaming.object;

import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.floor;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static org.mule.runtime.core.util.ConcurrencyUtils.safeUnlock;
import org.mule.runtime.core.internal.streaming.object.iterator.ConsumerIterator;
import org.mule.runtime.core.streaming.objects.InMemoryCursorIteratorConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BucketedObjectStreamBuffer<T> implements ObjectStreamBuffer<T> {

  private final ConsumerIterator<T> stream;
  private final InMemoryCursorIteratorConfig config;
  private final ReadWriteLock bucketsReadWriteLock = new ReentrantReadWriteLock();
  private final Lock bucketsReadLock = bucketsReadWriteLock.readLock();
  private final Lock bucketsWriteLock = bucketsReadWriteLock.writeLock();

  private List<Bucket<T>> buckets;
  private Bucket<T> currentBucket;
  private Position currentPosition;
  private Position maxPosition = null;

  public BucketedObjectStreamBuffer(ConsumerIterator<T> stream,
                                    InMemoryCursorIteratorConfig config) {
    this.stream = stream;
    this.config = config;
    initialiseBuckets();
  }

  @Override
  public T get(long i) {
    Position position = toPosition(i);
    if (maxPosition.compareTo(position) < 0) {
      throw new NoSuchElementException();
    }

    bucketsReadLock.lock();
    try {
      return getPresentItem(position).orElseGet(() -> {
        safeUnlock(bucketsReadLock);
        return fetch(position);
      });
    } finally {
      safeUnlock(bucketsReadLock);
    }
  }

  @Override
  public boolean hasNext(long position) {
    return false;
  }

  @Override
  public void close() {

  }

  private void initialiseBuckets() {
    int size = stream.size();
    if (size > 0) {
      maxPosition = toPosition(size);
      buckets = new ArrayList<>(maxPosition.bucketIndex + 1);
    } else {
      // equivalent to a null object
      maxPosition = new Position(MAX_VALUE, config.getBufferSizeIncrement());
      buckets = new ArrayList<>();
    }

    currentBucket = new Bucket<>(config.getInitialBufferSize());
    buckets.add(currentBucket);
    currentPosition = new Position(0, 0);
  }

  private Optional<T> getPresentItem(Position position) {
    if (position.bucketIndex < buckets.size()) {
      Bucket<T> bucket = buckets.get(position.bucketIndex);
      return bucket.get(position.itemIndex);
    }

    return empty();
  }

  private T fetch(Position position) {
    bucketsWriteLock.lock();

    try {
      return getPresentItem(position).orElseGet(() -> {
        T item = null;

        while (currentPosition.compareTo(position) < 0) {
          if (!stream.hasNext()) {
            throw new NoSuchElementException();
          }

          item = stream.next();
          if (currentBucket.add(item)) {
            currentPosition = currentPosition.advanceItem();
          } else {
            currentBucket = new Bucket<>(config.getBufferSizeIncrement());
            currentBucket.add(item);
            buckets.add(currentBucket);
            currentPosition = currentPosition.advanceBucket();
          }
        }

        return item;
      });
    } finally {
      bucketsWriteLock.unlock();
    }
  }

  private Position toPosition(long position) {
    int initialBufferSize = config.getInitialBufferSize();

    if (position < initialBufferSize) {
      return new Position(0, (int) position);
    }

    int bucketsDelta = config.getBufferSizeIncrement();
    long offset = position - initialBufferSize;

    int bucketIndex = (int) floor(offset / bucketsDelta);
    int itemIndex = (int) position - (initialBufferSize + (bucketIndex * bucketsDelta));

    return new Position(bucketIndex, itemIndex);
  }

  private class Position implements Comparable<Position> {

    private final int bucketIndex;
    private final int itemIndex;

    private Position(int bucketIndex, int itemIndex) {
      this.bucketIndex = bucketIndex;
      this.itemIndex = itemIndex;
    }

    private Position advanceItem() {
      return new Position(bucketIndex, itemIndex + 1);
    }

    private Position advanceBucket() {
      return new Position(bucketIndex + 1, 0);
    }

    @Override
    public int compareTo(Position o) {
      int compare = compare(bucketIndex, o.bucketIndex);
      if (compare == 0) {
        compare = compare(itemIndex, o.itemIndex);
      }

      return compare;
    }
  }

  private int compare(int left, int right) {
    if (left < right) {
      return -1;
    }

    if (left > right) {
      return 1;
    }

    return 0;
  }

  private class Bucket<T> {

    private final List<T> items;
    private final int capacity;

    private Bucket(int capacity) {
      this.capacity = capacity;
      this.items = new ArrayList<>(capacity);
    }

    private boolean add(T item) {
      if (items.size() < capacity) {
        items.add(item);
        return true;
      }

      return false;
    }

    private Optional<T> get(int index) {
      if (index < items.size()) {
        return ofNullable(items.get(index));
      }
      return empty();
    }
  }
}
