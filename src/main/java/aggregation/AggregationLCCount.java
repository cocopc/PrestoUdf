package aggregation;

import com.facebook.presto.operator.aggregation.state.SliceState;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.function.*;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.StandardTypes;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;

/*
计算留存(日留存、周留存、月留存)的聚合函数, 步骤一
限制条件:
1. 日留存最大 15 * 30
2. 周留存最大 12 * 8
3. 月留存最大 6 * 3

注: 2007-01-01正好为一个周的第一天，且为一个月的第一天

查询12月1号开始7天, 往后推15天的日留存
select lc_sum(xwho_state, 7, 15) from(
select lc_count(
date_diff('day', from_iso8601_timestamp('2007-01-01'), from_unixtime(xwhen)),
date_diff('day', from_iso8601_timestamp('2007-01-01'), from_iso8601_timestamp('2016-12-01')),
7, 15, xwhat, 'A,B', 'C,D') as xwho_state
from tablename
where (ds >= '2016-12-01' and ds < '2016-12-08' and xwhat in ('A', 'B')) or
    (ds >= '2016-12-02' and ds < '2016-12-23' and xwhat in ('C', 'D'))
group by xwho);
 */
@AggregationFunction("lc_count")
public class AggregationLCCount extends AggregationBase {

    private static final int FIRST = 2;
    private static final int SECOND = 8;
    private static final int INDEX = FIRST;

    @InputFunction
    public static void input(SliceState state,                                      // 每个用户的状态
                             @SqlType(StandardTypes.BIGINT) long xwhen,             // 当前事件的事件距离某固定日期的差值
                             @SqlType(StandardTypes.BIGINT) long xwhen_start,       // 当前查询的起始日期距离某固定日期的差值
                             @SqlType(StandardTypes.INTEGER) long first_length,     // 当前查询的first长度(15天, 12周, 6月)
                             @SqlType(StandardTypes.INTEGER) long second_length,    // 当前查询的second长度(30天, 8周, 3月)
                             @SqlType(StandardTypes.VARCHAR) Slice xwhat,           // 当前事件的名称, A,B,C,D
                             @SqlType(StandardTypes.VARCHAR) Slice events_start,    // 当前查询的起始事件列表, 逗号分隔
                             @SqlType(StandardTypes.VARCHAR) Slice events_end) {    // 当前查询的结束事件列表, 逗号分隔
        // 获取状态
        Slice slice = state.getSlice();

        // 判断是否需要初始化events
        if (!event_pos_dict_start.containsKey(events_start)) {
            init_events(events_start, 1);
        }

        // 判断是否需要初始化events
        if (!event_pos_dict_end.containsKey(events_end)) {
            init_events(events_end, 2);
        }

        // 初始化某一个用户的state, 分别存放不同事件在每个时间段的标示
        if (null == slice) {
            slice = Slices.allocate(FIRST + SECOND);
        }

        // 判读是否为起始事件
        if (event_pos_dict_start.get(events_start).containsKey(xwhat)) {
            int xindex_max = (int) first_length - 1;

            // 获取用户在当前index的状态
            short current_value = slice.getShort(0);
            if (current_value < max_value_array_short.get(xindex_max)) {
                // 获取下标
                int xindex = (int) (xwhen - xwhen_start);
                if (xindex >= 0 && xindex <= xindex_max) {
                    // 更新状态
                    slice.setShort(0, current_value | bit_array_short.get(xindex));
                }
            }

        }

        // 判断是否为结束事件
        if (event_pos_dict_end.get(events_end).containsKey(xwhat)) {
            int xindex_max = (int) (first_length + second_length - 1) - 1;

            // 获取用户在当前index的状态
            long current_value = slice.getLong(INDEX);
            if (current_value < max_value_array_long.get(xindex_max)) {
                // 获取下标
                int xindex = (int) (xwhen - (xwhen_start + 1));
                if (xindex >= 0 && xindex <= xindex_max) {
                    // 更新状态
                    slice.setLong(INDEX, current_value | bit_array_long.get(xindex));
                }
            }
        }

        // 返回结果
        state.setSlice(slice);
    }

    @CombineFunction
    public static void combine(SliceState state, SliceState otherState) {
        // 获取状态
        Slice slice = state.getSlice();
        Slice otherslice = otherState.getSlice();

        // 更新状态并返回结果
        if (null == slice) {
            state.setSlice(otherslice);
        } else {
            slice.setShort(0, slice.getShort(0) | otherslice.getShort(0));
            slice.setLong(INDEX, slice.getLong(INDEX) | otherslice.getLong(INDEX));
            state.setSlice(slice);
        }
    }

    @OutputFunction("array(" + StandardTypes.BIGINT + ")")
    public static void output(SliceState state, BlockBuilder out) {
        // 获取状态
        Slice slice = state.getSlice();

        // 构造结果: 当前用户在第一个事件中每一天(周/月)的状态, 和在第二个事件中每一天(周/月)的状态
        BlockBuilder blockBuilder = BigintType.BIGINT.createBlockBuilder(new BlockBuilderStatus(), 2);
        if (null == slice) {
            BigintType.BIGINT.writeLong(blockBuilder, 0);
            BigintType.BIGINT.writeLong(blockBuilder, 0);
        } else {
            BigintType.BIGINT.writeLong(blockBuilder, slice.getShort(0));
            BigintType.BIGINT.writeLong(blockBuilder, slice.getLong(INDEX));
        }

        // 返回结果
        out.writeObject(blockBuilder.build());
        out.closeEntry();
    }
}
