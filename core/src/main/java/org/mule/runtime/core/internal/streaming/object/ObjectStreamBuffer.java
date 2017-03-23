package org.mule.runtime.core.internal.streaming.object;

interface ObjectStreamBuffer<T> {

  T get(long position);

  boolean hasNext(long position);

  void close();
}
