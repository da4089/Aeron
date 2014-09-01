/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron;

import uk.co.real_logic.aeron.common.BufferBuilder;
import uk.co.real_logic.aeron.common.collections.Int2ObjectHashMap;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;

import java.util.function.Supplier;

import static uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor.*;

/**
 * {@link DataHandler} that sits in a chain-of-responsibilities pattern that re-assembles fragmented messages
 * so that next handler in the chain only sees unfragmented messages.
 *
 * Unfragmented messages are delegated without copy. Fragmented messages are copied to a temporary
 * buffer for reassembly before delegation.
 *
 * Session based buffers will be allocated and grown as necessary based on the size of messages to be assembled.
 *
 * When sessions go inactive {@see InactiveConnectionHandler}, it is possible to free the buffer by calling
 * {@link #freeSessionBuffer(int)}.
 */
public class FragmentAssemblyAdapter implements DataHandler
{
    private final DataHandler delegate;
    private final Int2ObjectHashMap<BufferBuilder> builderBySessionIdMap = new Int2ObjectHashMap<>();
    private Supplier<BufferBuilder> builderSupplier;

    /**
     * Construct an adapter to reassembly message fragments and delegate on only whole messages.
     *
     * @param delegate onto which whole messages are forwarded.
     */
    public FragmentAssemblyAdapter(final DataHandler delegate)
    {
        this(delegate, BufferBuilder.INITIAL_CAPACITY);
    }

    /**
     * Construct an adapter to reassembly message fragments and delegate on only whole messages.
     *
     * @param delegate onto which whole messages are forwarded.
     * @param initialBufferSize to be used for each session.
     */
    public FragmentAssemblyAdapter(final DataHandler delegate, final int initialBufferSize)
    {
        this.delegate = delegate;
        builderSupplier = () -> new BufferBuilder(initialBufferSize);
    }

    public void onData(final AtomicBuffer buffer, final int offset, final int length, final int sessionId, final byte flags)
    {
        if ((flags & UNFRAGMENTED) == UNFRAGMENTED)
        {
            delegate.onData(buffer, offset, length, sessionId, flags);
        }
        else if ((flags & BEGIN_FRAG) == BEGIN_FRAG)
        {
            final BufferBuilder builder = builderBySessionIdMap.getOrDefault(sessionId, builderSupplier);
            builder.reset().append(buffer, offset, length);
        }
        else if ((flags & END_FRAG) == END_FRAG)
        {
            final BufferBuilder builder = builderBySessionIdMap.getOrDefault(sessionId, builderSupplier);
            builder.append(buffer, offset, length);

            delegate.onData(builder.buffer(), 0, builder.limit(), sessionId, (byte)(flags | UNFRAGMENTED));
        }
        else
        {
            final BufferBuilder builder = builderBySessionIdMap.getOrDefault(sessionId, builderSupplier);
            builder.append(buffer, offset, length);
        }
    }

    /**
     * Free an existing session buffer to reduce memory pressure when a connection goes inactive or no more large messages
     * are expected.
     *
     * @param sessionId to have its buffer freed
     * @return true if a buffer has been freed otherwise false.
     */
    public boolean freeSessionBuffer(final int sessionId)
    {
        return null != builderBySessionIdMap.remove(sessionId);
    }
}