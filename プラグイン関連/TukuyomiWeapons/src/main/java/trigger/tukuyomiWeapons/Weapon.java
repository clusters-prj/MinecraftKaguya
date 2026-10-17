package trigger.tukuyomiWeapons;

import org.bukkit.Material;

/**
 * 武器定義。
 * material / modelData はリソースパック側の設定に合わせて変更してください。
 * damage < 0 または speed < 0 の項目は、素材のデフォルト値のままになります。
 * reach は攻撃リーチの倍率です(1.0 = 通常)。
 */
public enum Weapon {
    //            id              表示名           素材                    CMD   ダメージ 攻撃速度 リーチ
    LIGHT_SWORD  ("light_sword",   "光の剣",        Material.DIAMOND_SWORD, 1001, -1,    -1,     1.0),
    SAW_CLEAVER  ("saw_cleaver",   "ノコギリ鉈",    Material.IRON_SWORD,    1002, 8,     2.0,    1.0),
    REJECTION    ("rejection",     "拒絶",          Material.BLAZE_ROD,     1003, -1,    -1,     1.0),
    BEAST_CLEAVER("beast_cleaver", "獣肉断ち",      Material.IRON_AXE,      1004, 16,    1.5,    1.5),
    PILE_HAMMER  ("pile_hammer",   "パイルハンマー", Material.NETHERITE_AXE,  1005, 60,    0.1,    1.0 / 3.0),
    // 仕込み杖: 右クリックで変形前 ⇄ 変形後を切り替える。変形後のリーチは 4.5ブロック ÷ 通常3ブロック
    CANE_SHEATHED("cane_sheathed", "仕込み杖(変形前)", Material.WOODEN_SWORD, 1006, 10,    2.0,    1.0),
    CANE_DRAWN   ("cane_drawn",    "仕込み杖(変形後)", Material.NETHERITE_SWORD, 1007, 12,  1.6,    4.5 / 3.0),
    // FUSHI爆弾: 雪玉と同じ投擲物。着弾点で爆風が起きる
    FUSHI_BOMB   ("fushi_bomb",    "FUSHI爆弾",     Material.SNOWBALL,      1008, -1,    -1,     1.0),
    // もと光るダムダム弾(旧: 八千代の筍。IDは timed_bomb のまま): 投げる → 着弾時に3ダメージ → 0.5秒後に爆発して周囲に30ダメージ
    TIMED_BOMB   ("timed_bomb",    "もと光るダムダム弾", Material.SNOWBALL,      1009, -1,    -1,     1.0),
    // 自律稼働無人爆弾(月人): 低速(秒速3ブロック)で直進する投擲物。着弾で爆発、10秒で自己破壊
    TSUKIBITO    ("tsukibito_bomb", "自律稼働無人爆弾(月人)", Material.SNOWBALL, 1010, -1, -1, 1.0),
    // 彩葉特製、毒素マシマシ粉水パンケーキ: 雪玉型。着弾時、半径3ブロックに吐き気・盲目・爆音(本人にだけ聞こえる)。クールタイム15秒
    PANCAKE      ("pancake",       "彩葉特製、毒素マシマシ粉水パンケーキ", Material.SNOWBALL, 1011, -1, -1, 1.0),
    // キーホルダー: 光の剣を持っている間、オフハンドを埋める自動付与アイテム(効果なし)。/weapon では出さない
    KEYCHAIN     ("keychain",      "キーホルダー",   Material.PAPER,         1012, -1,    -1,     1.0),
    // 狩猟笛: 剣ベース(ダメージ10、攻撃速度は剣と同じ1.6)。手に持っている間、攻撃力増加Iと移動速度増加I
    HUNTING_HORN ("hunting_horn",  "狩猟笛",         Material.STONE_SWORD,   1013, 10,    1.6,    1.0),
    // ガンランス「MIKA-D0」: 斧ベース。攻撃速度 0.6、ダメージ22
    GUNLANCE     ("gunlance_mika_d0", "ガンランス「MIKA-D0」", Material.DIAMOND_AXE, 1014, 22, 0.6, 1.0),
    // ギター「共振波動」: 0.5秒チャージ(鈍足III)でウォーデンの衝撃波(24ダメージ)。クールタイム6秒
    GUITAR       ("guitar_resonance", "ギター「共振波動」", Material.GOLDEN_SWORD, 1015, -1, -1, 1.0),
    // 獣肉断ち(変形後): 斧ベース。ダメージ12・リーチ7ブロック・攻撃速度0.5。右クリックで獣肉断ち(変形前)に戻る
    BEAST_CLEAVER_DRAWN("beast_cleaver_drawn", "獣肉断ち(変形後)", Material.STONE_AXE, 1016, 12, 0.5, 7.0 / 3.0),
    // ノコギリ鉈(変形後): 剣ベース。ダメージ+2(10)・リーチ4.5ブロック・攻撃速度1。両形態とも、命中時に毒II(10秒)
    SAW_CLEAVER_DRAWN("saw_cleaver_drawn", "ノコギリ鉈(変形後)", Material.IRON_SWORD, 1017, 10, 1.0, 4.5 / 3.0);

    final String id, displayName;
    final Material material;
    final int modelData;
    final double damage, speed, reach;

    Weapon(String id, String displayName, Material material, int modelData, double damage, double speed, double reach) {
        this.id = id;
        this.displayName = displayName;
        this.material = material;
        this.modelData = modelData;
        this.damage = damage;
        this.speed = speed;
        this.reach = reach;
    }

    static Weapon byId(String id) {
        for (Weapon w : values()) if (w.id.equalsIgnoreCase(id)) return w;
        return null;
    }
}
