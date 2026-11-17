package com.personal.roleplay.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.personal.roleplay.domain.model.MessageRole
import com.personal.roleplay.data.local.dao.CharacterDao
import com.personal.roleplay.data.local.dao.MemoryDao
import com.personal.roleplay.data.local.dao.MemorySourceDao
import com.personal.roleplay.data.local.dao.MessageDao
import com.personal.roleplay.data.local.dao.ProviderDao
import com.personal.roleplay.data.local.dao.RequestLogDao
import com.personal.roleplay.data.local.dao.SessionDao
import com.personal.roleplay.data.local.dao.SettingDao
import com.personal.roleplay.data.local.dao.UsageDao
import com.personal.roleplay.data.local.dao.UserPersonaDao
import com.personal.roleplay.data.local.dao.WorldBookDao
import com.personal.roleplay.data.local.entity.AppSettingEntity
import com.personal.roleplay.data.local.entity.CharacterEntity
import com.personal.roleplay.data.local.entity.MemoryEntity
import com.personal.roleplay.data.local.entity.MemorySourceEntity
import com.personal.roleplay.data.local.entity.MessageEntity
import com.personal.roleplay.data.local.entity.ProviderConfigEntity
import com.personal.roleplay.data.local.entity.RequestLogEntity
import com.personal.roleplay.data.local.entity.SessionEntity
import com.personal.roleplay.data.local.entity.UsageRecordEntity
import com.personal.roleplay.data.local.entity.UserPersonaEntity
import com.personal.roleplay.data.local.entity.WorldBookEntity
import com.personal.roleplay.data.local.entity.WorldBookEntryEntity

private const val DATABASE_NAME = "roleplay.db"

@Database(
    entities = [
        CharacterEntity::class,
        WorldBookEntity::class,
        WorldBookEntryEntity::class,
        ProviderConfigEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        MemorySourceEntity::class,
        UsageRecordEntity::class,
        RequestLogEntity::class,
        UserPersonaEntity::class,
        AppSettingEntity::class
    ],
    version = 4,
    exportSchema = true
)
@androidx.room.TypeConverters(Converters::class)
abstract class RoleplayDatabase : RoomDatabase() {

    abstract fun characterDao(): CharacterDao
    abstract fun worldBookDao(): WorldBookDao
    abstract fun providerDao(): ProviderDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memorySourceDao(): MemorySourceDao
    abstract fun usageDao(): UsageDao
    abstract fun requestLogDao(): RequestLogDao
    abstract fun userPersonaDao(): UserPersonaDao
    abstract fun settingDao(): SettingDao

    companion object {

        /**
         * 刻意不使用 fallbackToDestructiveMigration：对话与记忆是本机唯一副本，
         * 版本升级时必须写显式 Migration，静默清库不可接受。
         *
         * 用 BundledSQLiteDriver 而不是系统 SQLite：Android 自带的 SQLite 没有编译 FTS5，
         * 记忆检索的粗筛会直接失效。这个驱动自带完整 SQLite（含 FTS5 与 bm25）。
         */
        fun create(context: Context): RoleplayDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                RoleplayDatabase::class.java,
                DATABASE_NAME
            )
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}

/**
 * 迁移基类，同时兼容两种 SQLite 驱动。
 *
 * Room 2.7 起，配置了 SQLiteDriver 时走 migrate(SQLiteConnection)；
 * 没配置驱动时才走旧的 migrate(SupportSQLiteDatabase)。这里用 BundledSQLiteDriver，
 * 因此只实现旧签名会在启动迁移时抛 NotImplementedError。
 * 两个签名都实现，换驱动时不用再改迁移。
 */
private abstract class SqlMigration(startVersion: Int, endVersion: Int) :
    Migration(startVersion, endVersion) {

    protected abstract val statements: List<String>

    override fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach { db.execSQL(it) }
    }

    override fun migrate(connection: SQLiteConnection) {
        statements.forEach { connection.execSql(it) }
    }
}

private fun SQLiteConnection.execSql(sql: String) {
    prepare(sql).use { it.step() }
}

/**
 * v1 → v2：会话支持多角色群聊，消息支持记录发言人。
 *
 * sessions 新增的 participant_ids 要求 NOT NULL，而 SQLite 无法给已有表改列约束，
 * 因此按 Room 的标准做法重建整张表再回填——老会话都是单人线，演员表就是它自己的角色。
 *
 * messages 只新增一个可空列，直接 ADD COLUMN；历史里的角色台词统一归给会话的角色。
 */
private val MIGRATION_1_2 = object : SqlMigration(1, 2) {
    override val statements = listOf(
        """
        CREATE TABLE IF NOT EXISTS sessions_new (
            id TEXT NOT NULL,
            character_id TEXT NOT NULL,
            participant_ids TEXT NOT NULL,
            title TEXT NOT NULL,
            user_persona TEXT,
            summary_segments TEXT NOT NULL,
            last_extracted_seq INTEGER NOT NULL,
            last_extracted_at INTEGER NOT NULL,
            rounds_since_extract INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            last_active_at INTEGER NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
        // char(34) 就是双引号：把单个 character_id 拼成 JSON 数组字面量 ["xxx"]
        """
        INSERT INTO sessions_new (
            id, character_id, participant_ids, title, user_persona, summary_segments,
            last_extracted_seq, last_extracted_at, rounds_since_extract,
            created_at, last_active_at
        )
        SELECT id, character_id,
               '[' || char(34) || character_id || char(34) || ']',
               title, user_persona, summary_segments,
               last_extracted_seq, last_extracted_at, rounds_since_extract,
               created_at, last_active_at
        FROM sessions
        """.trimIndent(),
        "DROP TABLE sessions",
        "ALTER TABLE sessions_new RENAME TO sessions",
        "CREATE INDEX IF NOT EXISTS index_sessions_character_id ON sessions(character_id)",
        "CREATE INDEX IF NOT EXISTS index_sessions_last_active_at ON sessions(last_active_at)",
        "ALTER TABLE messages ADD COLUMN speaker_character_id TEXT",
        "UPDATE messages SET speaker_character_id = " +
            "(SELECT character_id FROM sessions s WHERE s.id = messages.session_id) " +
            "WHERE role = '" + MessageRole.ASSISTANT.wire + "'"
    )
}

/**
 * v2 → v3：新增用户人设库。
 *
 * 人设从「每条会话各存一份文本」升级成「全局共享的库」：新表存人设，
 * 会话只加一个可空列引用它。
 *
 * sessions 只 ADD COLUMN 可空列，不需要重建表。刻意不回填 user_persona_id：
 * 老会话原有的 user_persona 正文就是它的人设，组装时查不到 id 会退回这段文本，
 * 因此升级后行为与升级前完全一致，用户不会感觉自己的扮演身份丢了。
 */
private val MIGRATION_2_3 = object : SqlMigration(2, 3) {
    override val statements = listOf(
        """
        CREATE TABLE IF NOT EXISTS user_persona (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            content TEXT NOT NULL,
            is_default INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(id)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_user_persona_is_default ON user_persona(is_default)",
        "CREATE INDEX IF NOT EXISTS index_user_persona_created_at ON user_persona(created_at)",
        "ALTER TABLE sessions ADD COLUMN user_persona_id TEXT"
    )
}

/**
 * v3 → v4：群聊一轮可让多个角色开口；新增全局设置表。
 *
 * group_turn_size 是 NOT NULL 列，SQLite 要求 ADD COLUMN 时必须带 DEFAULT，
 * 这里的默认值必须与 SessionEntity 上 @ColumnInfo(defaultValue = "2") 完全一致，
 * 否则 Room 启动时的 schema 校验会失败。老会话原来都是「一轮一人」，
 * 但群聊本来就是新功能，统一给 2 更符合现在的预期，单人线不读这个值。
 */
private val MIGRATION_3_4 = object : SqlMigration(3, 4) {
    override val statements = listOf(
        "ALTER TABLE sessions ADD COLUMN group_turn_size INTEGER NOT NULL DEFAULT 2",
        """
        CREATE TABLE IF NOT EXISTS app_setting (
            setting_key TEXT NOT NULL,
            value TEXT NOT NULL,
            PRIMARY KEY(setting_key)
        )
        """.trimIndent()
    )
}

/**
 * FTS5 虚表不再在这里建。
 *
 * 老版本在 onCreate 里建表，但系统 SQLite 没有 FTS5，失败后索引永久缺失；
 * 现在由 [MemoryFtsIndex] 第一次用到时懒建，并在索引为空时按 memory 表回填，
 * 这样已经装过旧版本的设备也能自动恢复，不必清库。
 */
