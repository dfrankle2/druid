/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Buildable dictionary for some comparable type. Values are unsorted, or rather sorted in the order which they are
 * added. A {@link SortedDimensionDictionary} can be constructed with a mapping of ids from this dictionary to the
 * sorted dictionary with the {@link #sort()} method.
 *
 * This dictionary is thread-safe.
 */
public class DimensionDictionary<T extends Comparable<T>>
{
  public static final int ABSENT_VALUE_ID = -1;

  @Nullable
  private T minValue = null;
  @Nullable
  private T maxValue = null;
  private volatile int idForNull = ABSENT_VALUE_ID;

  private final AtomicLong sizeInBytes = new AtomicLong(0L);
  private final Object2IntMap<T> valueToId = new Object2IntOpenHashMap<>();

  private final List<T> idToValue = new ArrayList<>();
  private final ReentrantReadWriteLock lock;

  public DimensionDictionary()
  {
    this.lock = new ReentrantReadWriteLock();
    valueToId.defaultReturnValue(ABSENT_VALUE_ID);
  }

  public int getId(@Nullable T value)
  {
    lock.readLock().lock();
    try {
      if (value == null) {
        return idForNull;
      }
      return valueToId.getInt(value);
    }
    finally {
      lock.readLock().unlock();
    }
  }

  @Nullable
  public T getValue(int id)
  {
    lock.readLock().lock();
    try {
      if (id == idForNull) {
        return null;
      }
      return idToValue.get(id);
    }
    finally {
      lock.readLock().unlock();
    }
  }

  public int size()
  {
    lock.readLock().lock();
    try {
      // using idToValue rather than valueToId because the valueToId doesn't account null value, if it is present.
      return idToValue.size();
    }
    finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Gets the current size of this dictionary in bytes.
   *
   * @throws IllegalStateException if size computation is disabled.
   */
  public long sizeInBytes()
  {
    if (!computeOnHeapSize()) {
      throw new IllegalStateException("On-heap size computation is disabled");
    }

    return sizeInBytes.get();
  }

  public int add(@Nullable T originalValue)
  {
    lock.writeLock().lock();
    try {
      if (originalValue == null) {
        if (idForNull == ABSENT_VALUE_ID) {
          idForNull = idToValue.size();
          idToValue.add(null);
        }
        return idForNull;
      }
      int prev = valueToId.getInt(originalValue);
      if (prev >= 0) {
        return prev;
      }
      final int index = idToValue.size();
      valueToId.put(originalValue, index);
      idToValue.add(originalValue);

      if (computeOnHeapSize()) {
        // Add size of new dim value and 2 references (valueToId and idToValue)
        sizeInBytes.addAndGet(estimateSizeOfValue(originalValue) + 2L * Long.BYTES);
      }

      minValue = minValue == null || minValue.compareTo(originalValue) > 0 ? originalValue : minValue;
      maxValue = maxValue == null || maxValue.compareTo(originalValue) < 0 ? originalValue : maxValue;
      return index;
    }
    finally {
      lock.writeLock().unlock();
    }
  }

  public T getMinValue()
  {
    lock.readLock().lock();
    try {
      return minValue;
    }
    finally {
      lock.readLock().unlock();
    }
  }

  public T getMaxValue()
  {
    lock.readLock().lock();
    try {
      return maxValue;
    }
    finally {
      lock.readLock().unlock();
    }
  }

  public int getIdForNull()
  {
    return idForNull;
  }

  public SortedDimensionDictionary<T> sort()
  {
    lock.readLock().lock();
    try {
      return new SortedDimensionDictionary<T>(idToValue, idToValue.size());
    }
    finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Estimates the size of the dimension value in bytes. This method is called
   * only when a new dimension value is being added to the lookup.
   *
   * @throws UnsupportedOperationException Implementations that want to estimate
   *                                       memory must override this method.
   */
  public long estimateSizeOfValue(T value)
  {
    throw new UnsupportedOperationException();
  }

  /**
   * Whether on-heap size of this dictionary should be computed.
   *
   * @return false, by default. Implementations that want to estimate memory
   * must override this method.
   */
  public boolean computeOnHeapSize()
  {
    return false;
  }

}
