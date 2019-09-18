package info.avalon566.shardingscaling.sync.mysql;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.json.JsonBinary;
import info.avalon566.shardingscaling.sync.core.AbstractRunner;
import info.avalon566.shardingscaling.sync.core.Channel;
import info.avalon566.shardingscaling.sync.core.RdbmsConfiguration;
import info.avalon566.shardingscaling.sync.core.Reader;
import info.avalon566.shardingscaling.sync.jdbc.Column;
import info.avalon566.shardingscaling.sync.jdbc.DataRecord;
import info.avalon566.shardingscaling.sync.jdbc.DbMetaDataUtil;
import info.avalon566.shardingscaling.sync.jdbc.JdbcUri;
import lombok.Setter;
import lombok.var;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author avalon566
 */
public class MysqlBinlogReader extends AbstractRunner implements Reader {

    private final static Logger LOGGER = LoggerFactory.getLogger(MysqlBinlogReader.class);

    private final HashMap<Long, String> tableMapCache = new HashMap<>();

    private final BinlogPosition binlogPosition = new BinlogPosition();

    private final RdbmsConfiguration rdbmsConfiguration;

    private final DbMetaDataUtil dbMetaDataUtil;

    @Setter
    private Channel channel;

    public MysqlBinlogReader(RdbmsConfiguration rdbmsConfiguration) {
        this.rdbmsConfiguration = rdbmsConfiguration;
        this.dbMetaDataUtil = new DbMetaDataUtil(rdbmsConfiguration);
    }

    @Override
    public void run() {
        start();
        read(channel);
    }

    public void markPosition() {
        try {
            try (var connection = DriverManager.getConnection(rdbmsConfiguration.getJdbcUrl(), rdbmsConfiguration.getUsername(), rdbmsConfiguration.getPassword())) {
                var ps = connection.prepareStatement("show master status");
                var rs = ps.executeQuery();
                rs.next();
                binlogPosition.setFilename(rs.getString(1));
                binlogPosition.setPosition(rs.getLong(2));
                ps = connection.prepareStatement("show variables like 'server_id'");
                rs = ps.executeQuery();
                rs.next();
                binlogPosition.setServerId(rs.getString(2));
            }
        } catch (Exception e) {
            throw new RuntimeException("markPosition error", e);
        }
    }

    @Override
    public void read(final Channel channel) {
        final var uri = new JdbcUri(rdbmsConfiguration.getJdbcUrl());
        BinaryLogClient client = new BinaryLogClient(uri.getHostname(), uri.getPort(), rdbmsConfiguration.getUsername(), rdbmsConfiguration.getPassword());
        client.setBinlogFilename(binlogPosition.getFilename());
        client.setBinlogPosition(binlogPosition.getPosition());
        EventDeserializer eventDeserializer = new EventDeserializer();
        client.setEventDeserializer(eventDeserializer);
        client.registerEventListener(new BinaryLogClient.EventListener() {
            @Override
            public void onEvent(Event event) {
                if (null != event.getData()) {
                    if (event.getData() instanceof WriteRowsEventData) {
                        var wred = (WriteRowsEventData) event.getData();
                        for (Serializable[] row : wred.getRows()) {
                            if (filter(uri.getDatabase(), tableMapCache.get(wred.getTableId()))) {
                                return;
                            }
                            var record = new DataRecord(row.length);
                            record.setFullTableName(tableMapCache.get(wred.getTableId()));
                            record.setType("insert");
                            for (int i = 0; i < row.length; i++) {
                                record.addColumn(new Column(getColumnValue(record.getTableName(), i, row[i]), true));
                            }
                            channel.pushRecord(record);
                        }
                    } else if (event.getData() instanceof UpdateRowsEventData) {
                        var ured = (UpdateRowsEventData) event.getData();
                        for (Map.Entry<Serializable[], Serializable[]> row : ured.getRows()) {
                            if (filter(uri.getDatabase(), tableMapCache.get(ured.getTableId()))) {
                                return;
                            }
                            var record = new DataRecord(row.getValue().length);
                            record.setFullTableName(tableMapCache.get(ured.getTableId()));
                            record.setType("update");
                            for (int i = 0; i < row.getValue().length; i++) {
                                var oldValue = getColumnValue(record.getTableName(), i, row.getKey()[i]);
                                var newValue = getColumnValue(record.getTableName(), i, row.getValue()[i]);
                                record.addColumn(new Column(newValue, newValue.equals(oldValue)));
                            }
                            channel.pushRecord(record);
                        }
                    } else if (event.getData() instanceof DeleteRowsEventData) {
                        var dred = (DeleteRowsEventData) event.getData();
                        for (Serializable[] row : dred.getRows()) {
                            if (filter(uri.getDatabase(), tableMapCache.get(dred.getTableId()))) {
                                return;
                            }
                            var record = new DataRecord(row.length);
                            record.setFullTableName(tableMapCache.get(dred.getTableId()));
                            record.setType("delete");
                            for (int i = 0; i < row.length; i++) {
                                record.addColumn(new Column(getColumnValue(record.getTableName(), i, row[i]), true));
                            }
                            channel.pushRecord(record);
                        }
                    } else if (event.getData() instanceof TableMapEventData) {
                        var tmed = (TableMapEventData) event.getData();
                        tableMapCache.put(tmed.getTableId(), String.format("%s.%s", tmed.getDatabase(), tmed.getTable()));
                    }
                }
            }
        });
        try {
            client.connect();
        } catch (Exception ex) {
            // retry
        }
    }

    @Override
    public List<RdbmsConfiguration> split(int concurrency) {
        return Arrays.asList(rdbmsConfiguration);
    }

    private boolean filter(String database, String fullTableName) {
        return !fullTableName.startsWith(database);
    }

    private Object getColumnValue(String tableName, int index, Serializable data) {
        var columns = dbMetaDataUtil.getColumNames(tableName);
        try {
            var type = columns.get(index).getColumnTypeName();
            if ("JSON".equals(type)) {
                return JsonBinary.parseAsString((byte[]) data);
            } else {
                return data;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}