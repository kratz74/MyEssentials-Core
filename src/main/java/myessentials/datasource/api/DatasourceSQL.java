package myessentials.datasource.api;

import myessentials.MyEssentialsCore;
import myessentials.datasource.api.bridge.BridgeMySQL;
import myessentials.datasource.api.bridge.BridgeSQL;
import myessentials.datasource.api.bridge.BridgeSQLite;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Datasource class which contains most functionality needed for a database connection.
 * Database connection initialization is done on instantiation.
 * Extend this and add all the load/save methods you want right in the extended class.
 */
public abstract class DatasourceSQL {

    protected Logger LOG;

    protected String prefix = "";
    protected BridgeSQL bridge;
    protected Schema schema;

    public ConfigurationNode databaseTypeNode;
    /*
    public ConfigProperty<String> databaseTypeNode = new ConfigProperty<String>(
            "type", "datasource",
            "Specifies the database engine that is being used.",
            "SQLite");*/

    public DatasourceSQL(Logger log, CommentedConfigurationNode rootNode, Schema schema) {
        this.LOG = log;
        this.schema = schema;
        configure(rootNode);
        schema.initializeUpdates(bridge);
        try {
            doUpdates();
        } catch (SQLException ex) {
            LOG.error("Failed to run database updates!");
            LOG.error(ExceptionUtils.getStackTrace(ex));
        }
        loadAll();
        checkAll();
    }

    public abstract boolean loadAll();

    public abstract boolean checkAll();

    public boolean stop() {
        try {
            bridge.getConnection().close();
            return true;
        } catch (SQLException e) {
            MyEssentialsCore.instance.LOG.error("Failed to close connection to database.");
            MyEssentialsCore.instance.LOG.error(ExceptionUtils.getStackTrace(e));
            return false;
        }
    }

    private void configure(CommentedConfigurationNode rootNode) {
        CommentedConfigurationNode databaseTypeNode = rootNode.getNode("datasource", "type");
        if (databaseTypeNode.isVirtual()) {
            databaseTypeNode.setValue("sqlite")
                            .setComment("The database engine used for this mod");
        }
        String databaseType = databaseTypeNode.getString();

        if (databaseType.equalsIgnoreCase("sqlite")) {
            bridge = new BridgeSQLite(rootNode);
        } else if (databaseType.equalsIgnoreCase("mysql")) {
            bridge = new BridgeMySQL(rootNode);
        }
    }

    protected boolean hasTable(String tableName) {
        try {
            DatabaseMetaData meta = bridge.getConnection().getMetaData();
            ResultSet rs = meta.getTables(null, null, prefix + tableName, null);
            return rs.next();
        } catch (Exception ex) {
            LOG.error("Failed to check for table existence.");
            LOG.error(ExceptionUtils.getStackTrace(ex));
            return false;
        }
    }

    protected PreparedStatement prepare(String sql, boolean returnGenerationKeys) {
        try {
            return bridge.getConnection().prepareStatement(sql, returnGenerationKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS);
        } catch (SQLException e) {
            LOG.error(sql);
            LOG.error(ExceptionUtils.getStackTrace(e));
        }
        return null;
    }

    protected void doUpdates() throws SQLException {
        List<String> ids = new ArrayList<String>();
        PreparedStatement statement;
        if(hasTable("Updates")) {
            statement = prepare("SELECT id FROM " + prefix + "Updates", false);
            ResultSet rs = statement.executeQuery();
            while (rs.next()) {
                ids.add(rs.getString("id"));
            }
        }

        for (Schema.DBUpdate update : schema.updates) {
            if (ids.contains(update.id)) {
                continue; // Skip if update is already done
            }

            try {
                LOG.info("Running update {} - {}", update.id, update.desc);
                statement = prepare(update.statement, false);
                statement.execute();

                // Insert the update key so as to not run the update again
                statement = prepare("INSERT INTO " + prefix + "Updates (id,description) VALUES(?,?)", true);
                statement.setString(1, update.id);
                statement.setString(2, update.desc);
                statement.executeUpdate();
            } catch (SQLException e) {
                LOG.error("Update ({} - {}) failed to apply!", update.id, update.desc);
                LOG.error(ExceptionUtils.getStackTrace(e));
                throw e; // Throws back up to force safemode
            }
        }
    }

    public BridgeSQL getBridge() {
        return this.bridge;
    }
}
