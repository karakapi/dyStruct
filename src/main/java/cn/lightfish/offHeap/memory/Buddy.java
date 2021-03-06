package cn.lightfish.offHeap.memory;

import cn.lightfish.offHeap.DyStruct;
import cn.lightfish.offHeap.StructInfo;
import cn.lightfish.offHeap.Type;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

import static cn.lightfish.offHeap.DyStruct.*;

public class Buddy {
    /*dystruct offset false*/
    public final static long LEVEL = 0L;
    public final static long TREE = 4L;
    static final StructInfo buddy = DyStruct.build("Buddy",
            "level", Type.Int,
            "tree", Type.Byte);
    final static byte NODE_UNUSED = 0;
    final static byte NODE_USED = 1;
    final static byte NODE_SPLIT = 2;
    final static byte NODE_FULL = 3;

    static Unsafe unsafe;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static long buddy_new(int level) {
        long size = 1 << level;
        long self = unsafe.allocateMemory(5 + /*sizeof(uint8_t)*/8 * (size * 2 - 2));
        unsafe.putInt(/*Buddy*/self, LEVEL, level);
        unsafe.setMemory($offset(self, TREE), size * 2 - 1, NODE_UNUSED);
        buddy_dump(self);
        return self;
    }

    public static void
    buddy_delete(long self) {
        unsafe.freeMemory(self);
    }

    static boolean
    is_pow_of_2(long x) {
        return !((x & (x - 1)) > 0);// op
    }

    static long
    next_pow_of_2(long x) {
        if (is_pow_of_2(x))
            return x;
        x |= x >> 1;
        x |= x >> 2;
        x |= x >> 4;
        x |= x >> 8;
        x |= x >> 16;
        return x + 1;
    }

    public static long
    _index_offset(long index, int level, int max_level) {
        return ((index + 1) - (1 << level)) << (max_level - level);
    }

    public static void
    _mark_parent(long self, long index) {
        for (; ; ) {
            long buddy = index - 1 + (index & 1) * 2;
            if (buddy > 0 && ($address(/*Buddy*/$offset(/*Buddy*/self, TREE), buddy) == NODE_USED || $address(/*Buddy*/$offset(/*Buddy*/self, TREE), buddy) == NODE_FULL)) {
                index = (index + 1) / 2 - 1;
                $address(/*Buddy*/$byte(/*Buddy*/self, TREE), index, NODE_FULL);
            } else {
                return;
            }
        }
    }

    public static long
    buddy_alloc(long self, long s) {
        long size;
        if (s == 0) {
            size = 1;
        } else {
            size = next_pow_of_2(s);
        }
        int length = 1 << $int(/*Buddy*/self, LEVEL);

        if (size > length)
            return -1;

        long index = 0L;
        int level = 0;

        while (index >= 0) {
            if (size == length) {
                if ($byte(/*Buddy*/$offset(/*Buddy*/self, TREE), index) == NODE_UNUSED) {
                    $byte(/*Buddy*/$offset(/*Buddy*/self, TREE), index, NODE_USED);
                    _mark_parent(self, index);
                    return _index_offset(index, level, $int(/*Buddy*/self, LEVEL));
                }
            } else {
                // size < length
                switch ($byte(/*Buddy*/$offset(/*Buddy*/self, TREE), index)) {
                    case NODE_USED:
                    case NODE_FULL:
                        break;
                    case NODE_UNUSED:
                        // split first
                        $int(/*Buddy*/$offset(/*Buddy*/self, TREE), index, NODE_SPLIT);
                        $int(/*Buddy*/$offset(/*Buddy*/self, TREE), index * 2 + 1, NODE_UNUSED);
                        $int(/*Buddy*/$offset(/*Buddy*/self, TREE), index * 2 + 2, NODE_UNUSED);
                    default:
                        index = index * 2 + 1;
                        length /= 2;
                        level++;
                        continue;
                }
            }
            if ((index & 1) > 0) {
                ++index;
                continue;
            }
            for (; ; ) {
                level--;
                length *= 2;
                index = (index + 1) / 2 - 1;
                if (index < 0)
                    return -1;
                if ((index & 1) > 0) {
                    ++index;
                    break;
                }
            }
        }

        return -1;
    }

    public static void
    _combine(long self, long index) {
        for (; ; ) {
            long buddy = index - 1 + (index & 1) * 2;
            if (buddy < 0 || $byte(/*Buddy*/$offset(/*Buddy*/self, TREE), buddy) != NODE_UNUSED) {
                $byte(/*Buddy*/$offset(/*Buddy*/self, TREE), index, NODE_UNUSED);
                while (((index = (index + 1) / 2 - 1) >= 0) && $byte(/*Buddy*/$offset(/*Buddy*/self, TREE), index) == NODE_FULL) {
                    $byte(/*Buddy*/$offset(/*Buddy*/self, TREE), index, NODE_SPLIT);
                }
                return;
            }
            index = (index + 1) / 2 - 1;
        }
    }

    public static void
    buddy_free(long self, long offset) {
        assert (offset < (1L << $int(/*Buddy*/self, LEVEL)));
        long left = 0L;
        long length = 1L << $int(/*Buddy*/self, LEVEL);
        long index = 0L;

        for (; ; ) {
            switch ($byte(/*Buddy*/$offset(/*Buddy*/self, TREE), index)) {
                case NODE_USED:
                    assert (offset == left);
                    _combine(self, index);
                    return;
                case NODE_UNUSED:
                    assert (false);
                    return;
                default:
                    length /= 2;
                    if (offset < left + length) {
                        index = index * 2 + 1;
                    } else {
                        left += length;
                        index = index * 2 + 2;
                    }
                    break;
            }
        }
    }

    public static long
    buddy_size(long self, long offset) {
        assert (offset < (1L << $int(/*Buddy*/self, LEVEL)));
        long left = 0L;
        long length = 1L << $int(/*Buddy*/self, LEVEL);
        long index = 0L;

        for (; ; ) {
            switch ($byte(/*Buddy*/$offset(/*Buddy*/self, TREE), index)) {
                case NODE_USED:
                    assert (offset == left);
                    return length;
                case NODE_UNUSED:
                    assert (false);
                    return length;
                default:
                    length /= 2;
                    if (offset < left + length) {
                        index = index * 2 + 1;
                    } else {
                        left += length;
                        index = index * 2 + 2;
                    }
                    break;
            }
        }
    }

    public static void
    _dump(long self, long index, int level) {
        switch ($byte(/*Buddy*/$offset(/*Buddy*/self, TREE), index)) {
            case NODE_UNUSED:
                printf("(%d:%d)", _index_offset(index, level, $int(/*Buddy*/self, LEVEL)), (1 << ($int(/*Buddy*/self, LEVEL) - level)));
                break;
            case NODE_USED:
                printf("[%d:%d]", _index_offset(index, level, $int(/*Buddy*/self, LEVEL)), 1 << ($int(/*Buddy*/self, LEVEL) - level));
                break;
            case NODE_FULL:
                printf("{");
                _dump(self, index * 2 + 1, level + 1);
                _dump(self, index * 2 + 2, level + 1);
                printf("}");
                break;
            default:
                printf("(");
                _dump(self, index * 2 + 1, level + 1);
                _dump(self, index * 2 + 2, level + 1);
                printf(")");
                break;
        }
    }

    public static void
    buddy_dump(long self) {
        _dump(self, 0, 0);
        printf("\n");
    }

    public static void printf(String tmp, Object... args) {
        System.out.printf(tmp, args);
    }

    public static void main(String[] args) {

    }
}
