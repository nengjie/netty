/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.util.internal.ReferenceCountUpdater;

/**
 * Abstract base class for {@link ByteBuf} implementations that count references.
 * 1.内存类型
 * 堆内存字节缓冲区( HeapByteBuf )：底层为 JVM 堆内的字节数组，其特点是申请和释放效率较高。但是如果要进行 Socket 的 I/O 读写，
 * 需要额外多做一次内存复制，需要将堆内存对应的缓冲区复制到内核 Channel 中，性能可能会有一定程度的损耗。
 *
 * 直接内存字节缓冲区( DirectByteBuf )：堆外内存，为操作系统内核空间的字节数组，它由操作系统直接管理和操作，
 * 其申请和释放的效率会慢于堆缓冲区。但是将它写入或者从 SocketChannel 中读取时，会少一次内存复制，这样可以大大提高 I/O 效率，实现零拷贝。
 *
 * 2.对象池
 * 基于对象池( PooledByteBuf )：基于对象池的 ByteBuf 可以重用 ByteBuf ，内部维护着一个对象池，当对象释放后会归还给对象池，
 * 这样就可以循环地利用创建的 ByteBuf，提升内存的使用率，降低由于高负载导致的频繁 GC。当需要大量且频繁创建缓冲区时，推荐使用该类缓冲区。
 *
 * 不使用对象池( UnpooledByteBuf )：对象池的管理和维护会比较困难，所以在不需要创建大量缓冲区对象时，推荐使用此类缓冲区。
 *
 * 3.Unsafe类
 * 使用 Unsafe ：基于 Java sun.misc.Unsafe.Unsafe 的 API ，直接访问内存中的数据。
 * 不使用 Unsafe ： 基于 HeapByteBuf 和 DirectByteBuf 的标准 API ，进行访问对应的数据。
 * （知乎贴：https://www.zhihu.com/question/29266773）
 *
 * 默认情况下，使用 PooledUnsafeDirectByteBuf
 */
public abstract class AbstractReferenceCountedByteBuf extends AbstractByteBuf {
    private static final long REFCNT_FIELD_OFFSET =
            ReferenceCountUpdater.getUnsafeOffset(AbstractReferenceCountedByteBuf.class, "refCnt");
    private static final AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> AIF_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(AbstractReferenceCountedByteBuf.class, "refCnt");

    private static final ReferenceCountUpdater<AbstractReferenceCountedByteBuf> updater =
            new ReferenceCountUpdater<AbstractReferenceCountedByteBuf>() {
        @Override
        protected AtomicIntegerFieldUpdater<AbstractReferenceCountedByteBuf> updater() {
            return AIF_UPDATER;
        }
        @Override
        protected long unsafeOffset() {
            return REFCNT_FIELD_OFFSET;
        }
    };

    // Value might not equal "real" reference count, all access should be via the updater
    @SuppressWarnings("unused")
    private volatile int refCnt = updater.initialValue();

    protected AbstractReferenceCountedByteBuf(int maxCapacity) {
        super(maxCapacity);
    }

    @Override
    boolean isAccessible() {
        // Try to do non-volatile read for performance as the ensureAccessible() is racy anyway and only provide
        // a best-effort guard.
        return updater.isLiveNonVolatile(this);
    }

    @Override
    public int refCnt() {
        return updater.refCnt(this);
    }

    /**
     * An unsafe operation intended for use by a subclass that sets the reference count of the buffer directly
     */
    protected final void setRefCnt(int refCnt) {
        updater.setRefCnt(this, refCnt);
    }

    /**
     * An unsafe operation intended for use by a subclass that resets the reference count of the buffer to 1
     */
    protected final void resetRefCnt() {
        updater.resetRefCnt(this);
    }

    @Override
    public ByteBuf retain() {
        return updater.retain(this);
    }

    @Override
    public ByteBuf retain(int increment) {
        return updater.retain(this, increment);
    }

    @Override
    public ByteBuf touch() {
        return this;
    }

    @Override
    public ByteBuf touch(Object hint) {
        return this;
    }

    @Override
    public boolean release() {
        return handleRelease(updater.release(this));
    }

    @Override
    public boolean release(int decrement) {
        return handleRelease(updater.release(this, decrement));
    }

    private boolean handleRelease(boolean result) {
        if (result) {
            deallocate();
        }
        return result;
    }

    /**
     * Called once {@link #refCnt()} is equals 0.
     */
    protected abstract void deallocate();
}
