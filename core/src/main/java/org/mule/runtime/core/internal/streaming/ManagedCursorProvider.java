/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.streaming;

import org.mule.runtime.api.streaming.Cursor;
import org.mule.runtime.api.streaming.CursorProvider;

public abstract class ManagedCursorProvider<T extends Cursor> implements CursorProvider<T> {

  private final CursorProvider<T> delegate;
  private final CursorManager cursorManager;
  private final CursorProviderHandle cursorProviderHandle;

  protected ManagedCursorProvider(CursorProviderHandle cursorProviderHandle, CursorManager cursorManager) {
    this.delegate = (CursorProvider<T>) cursorProviderHandle.getCursorProvider();
    this.cursorProviderHandle = cursorProviderHandle;
    this.cursorManager = cursorManager;
  }

  @Override
  public final T openCursor() {
    T cursor = delegate.openCursor();
    cursorManager.onOpen(cursor, cursorProviderHandle);
    return managedCursor(cursor, cursorProviderHandle);
  }

  protected abstract T managedCursor(T cursor, CursorProviderHandle handle);

  @Override
  public void releaseResources() {
    delegate.releaseResources();
  }

  @Override
  public void close() {
    delegate.close();
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }
}
