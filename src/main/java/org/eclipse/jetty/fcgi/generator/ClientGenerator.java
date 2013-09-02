//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;

public class ClientGenerator extends Generator
{
    // To keep the algorithm simple, and given that the max length of a
    // frame is 0xFF_FF we allow the max length of a name (or value) to be
    // 0x7F_FF - 4 (the 4 is to make room for the name (or value) length).
    public static final int MAX_PARAM_LENGTH = 0x7F_FF - 4;

    private final ByteBufferPool byteBufferPool;

    public ClientGenerator(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    public Result generateRequestHeaders(int id, Fields fields, Callback callback)
    {
        id = id & 0xFF_FF;

        Charset utf8 = Charset.forName("UTF-8");
        List<byte[]> bytes = new ArrayList<>(fields.size() * 2);
        int fieldsLength = 0;
        for (Fields.Field field : fields)
        {
            String name = field.name();
            byte[] nameBytes = name.getBytes(utf8);
            if (nameBytes.length > MAX_PARAM_LENGTH)
                throw new IllegalArgumentException("Field name " + name + " exceeds max length " + MAX_PARAM_LENGTH);
            bytes.add(nameBytes);

            String value = field.value();
            byte[] valueBytes = value.getBytes(utf8);
            if (valueBytes.length > MAX_PARAM_LENGTH)
                throw new IllegalArgumentException("Field value " + value + " exceeds max length " + MAX_PARAM_LENGTH);
            bytes.add(valueBytes);

            int nameLength = nameBytes.length;
            fieldsLength += bytesForLength(nameLength);

            int valueLength = valueBytes.length;
            fieldsLength += bytesForLength(valueLength);

            fieldsLength += nameLength;
            fieldsLength += valueLength;
        }

        // Worst case FCGI_PARAMS frame: long name + long value - both of MAX_PARAM_LENGTH
        int maxCapacity = 4 + 4 + 2 * MAX_PARAM_LENGTH;

        // One FCGI_BEGIN_REQUEST + N FCGI_PARAMS + one last FCGI_PARAMS
        int numberOfFrames = 1 + (fieldsLength / maxCapacity + 1) + 1;
        Result result = new Result(byteBufferPool, callback, numberOfFrames);

        ByteBuffer beginRequestBuffer = byteBufferPool.acquire(16, false);
        BufferUtil.clearToFill(beginRequestBuffer);
        result.add(beginRequestBuffer, true);

        // Generate the FCGI_BEGIN_REQUEST frame
        beginRequestBuffer.putInt(0x01_01_00_00 + id);
        beginRequestBuffer.putInt(0x00_08_00_00);
        beginRequestBuffer.putLong(0x00_01_01_00_00_00_00_00L);
        beginRequestBuffer.flip();

        int index = 0;
        while (fieldsLength > 0)
        {
            int capacity = 8 + Math.min(maxCapacity, fieldsLength);
            ByteBuffer buffer = byteBufferPool.acquire(capacity, true);
            BufferUtil.clearToFill(buffer);
            result.add(buffer, true);

            // Generate the FCGI_PARAMS frame
            buffer.putInt(0x01_04_00_00 + id);
            buffer.putShort((short)0);
            buffer.putShort((short)0);
            capacity -= 8;

            int length = 0;
            while (index < bytes.size())
            {
                byte[] nameBytes = bytes.get(index);
                int nameLength = nameBytes.length;
                byte[] valueBytes = bytes.get(index + 1);
                int valueLength = valueBytes.length;

                int required = bytesForLength(nameLength) + bytesForLength(valueLength) + nameLength + valueLength;
                if (required > capacity)
                    break;

                putParamLength(buffer, nameLength);
                putParamLength(buffer, valueLength);
                buffer.put(nameBytes);
                buffer.put(valueBytes);

                length += required;
                fieldsLength -= required;
                capacity -= required;
                index += 2;
            }

            buffer.putShort(4, (short)length);
            buffer.flip();
        }


        ByteBuffer lastParamsBuffer = byteBufferPool.acquire(8, false);
        BufferUtil.clearToFill(lastParamsBuffer);
        result.add(lastParamsBuffer, true);

        // Generate the last FCGI_PARAMS frame
        lastParamsBuffer.putInt(0x01_04_00_00 + id);
        lastParamsBuffer.putInt(0x00_00_00_00);
        lastParamsBuffer.flip();

        return result;
    }

    private int putParamLength(ByteBuffer buffer, int length)
    {
        int result = bytesForLength(length);
        if (result == 4)
            buffer.putInt(length | 0x80_00_00_00);
        else
            buffer.put((byte)length);
        return result;
    }

    private int bytesForLength(int length)
    {
        return length > 127 ? 4 : 1;
    }

    public ByteBuffer generateRequestContent(ByteBuffer content)
    {
        return content == null ? generateContent(FCGI.FrameType.STDIN, 0) : generateContent(FCGI.FrameType.STDIN, content.remaining());
    }
}
