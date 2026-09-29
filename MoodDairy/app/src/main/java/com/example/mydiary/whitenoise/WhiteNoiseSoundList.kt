package com.example.mydiary.whitenoise

import androidx.compose.ui.graphics.Color
import com.example.mydiary.R

/**
 * 白噪音完整音频列表
 * 自动生成，包含所有111个音频文件
 */
object WhiteNoiseSoundList {
    
    fun getAllSounds(): List<WhiteNoiseSound> {
        return listOf(
            // 动物声音
            WhiteNoiseSound("beehive", "蜂巢", "ANIMALS", R.raw.beehive, "🐝", Color(0xFFFFB300)),
            WhiteNoiseSound("birds", "鸟鸣", "ANIMALS", R.raw.birds, "🐦", Color(0xFF42A5F5)),
            WhiteNoiseSound("cat_purring", "猫咪呼噜", "ANIMALS", R.raw.cat_purring, "🐱", Color(0xFFFF7043)),
            WhiteNoiseSound("chickens", "鸡叫", "ANIMALS", R.raw.chickens, "🐔", Color(0xFFFFCA28)),
            WhiteNoiseSound("cows", "牛叫", "ANIMALS", R.raw.cows, "🐄", Color(0xFF8D6E63)),
            WhiteNoiseSound("crickets", "蟋蟀", "ANIMALS", R.raw.crickets, "🦗", Color(0xFF66BB6A)),
            WhiteNoiseSound("crows", "乌鸦", "ANIMALS", R.raw.crows, "🐦‍⬛", Color(0xFF424242)),
            WhiteNoiseSound("dog_barking", "狗叫", "ANIMALS", R.raw.dog_barking, "🐕", Color(0xFFA1887F)),
            WhiteNoiseSound("frog", "青蛙", "ANIMALS", R.raw.frog, "🐸", Color(0xFF66BB6A)),
            WhiteNoiseSound("horse_gallop", "马蹄声", "ANIMALS", R.raw.horse_gallop, "🐴", Color(0xFF8D6E63)),
            WhiteNoiseSound("owl", "猫头鹰", "ANIMALS", R.raw.owl, "🦉", Color(0xFF795548)),
            WhiteNoiseSound("seagulls", "海鸥", "ANIMALS", R.raw.seagulls, "🕊️", Color(0xFF90CAF9)),
            WhiteNoiseSound("sheep", "羊叫", "ANIMALS", R.raw.sheep, "🐑", Color(0xFFEEEEEE)),
            WhiteNoiseSound("whale", "鲸鱼", "ANIMALS", R.raw.whale, "🐋", Color(0xFF42A5F5)),
            WhiteNoiseSound("wolf", "狼嚎", "ANIMALS", R.raw.wolf, "🐺", Color(0xFF757575)),
            WhiteNoiseSound("woodpecker", "啄木鸟", "ANIMALS", R.raw.woodpecker, "🪶", Color(0xFF8D6E63)),
            
            // 自然声音
            WhiteNoiseSound("campfire", "篝火", "NATURE", R.raw.campfire, "🔥", Color(0xFFFF8A65)),
            WhiteNoiseSound("droplets", "水滴", "NATURE", R.raw.droplets, "💧", Color(0xFF4FC3F7)),
            WhiteNoiseSound("field", "田野", "NATURE", R.raw.field, "🌾", Color(0xFFDCE775)),
            WhiteNoiseSound("forest", "森林", "NATURE", R.raw.forest, "🌲", Color(0xFF81C784)),
            WhiteNoiseSound("howling_wind", "呼啸的风", "NATURE", R.raw.howling_wind, "🌬️", Color(0xFF90CAF9)),
            WhiteNoiseSound("jungle", "丛林", "NATURE", R.raw.jungle, "🌴", Color(0xFF66BB6A)),
            WhiteNoiseSound("lake", "湖泊", "NATURE", R.raw.lake, "🏞️", Color(0xFF4FC3F7)),
            WhiteNoiseSound("river", "河流", "NATURE", R.raw.river, "🌊", Color(0xFF42A5F5)),
            WhiteNoiseSound("walk_in_snow", "雪地行走", "NATURE", R.raw.walk_in_snow, "❄️", Color(0xFFE3F2FD)),
            WhiteNoiseSound("walk_on_gravel", "碎石路", "NATURE", R.raw.walk_on_gravel, "🪨", Color(0xFF9E9E9E)),
            WhiteNoiseSound("walk_on_leaves", "落叶行走", "NATURE", R.raw.walk_on_leaves, "🍂", Color(0xFFFF8A65)),
            WhiteNoiseSound("waterfall", "瀑布", "NATURE", R.raw.waterfall, "💦", Color(0xFF4FC3F7)),
            WhiteNoiseSound("waves", "海浪", "NATURE", R.raw.waves, "🌊", Color(0xFF4FC3F7)),
            WhiteNoiseSound("wind_in_trees", "树林风声", "NATURE", R.raw.wind_in_trees, "🌲", Color(0xFF81C784)),
            WhiteNoiseSound("wind", "风声", "NATURE", R.raw.wind, "💨", Color(0xFF90CAF9)),
            
            // 噪音
            WhiteNoiseSound("brown_noise", "棕噪音", "NOISE", R.raw.brown_noise, "🟤", Color(0xFF8D6E63)),
            WhiteNoiseSound("eating_chips", "吃薯片", "NOISE", R.raw.eating_chips, "🍟", Color(0xFFFFCA28)),
            WhiteNoiseSound("piano", "钢琴", "NOISE", R.raw.piano, "🎹", Color(0xFF424242)),
            WhiteNoiseSound("pink_noise", "粉噪音", "NOISE", R.raw.pink_noise, "🎵", Color(0xFFF48FB1)),
            WhiteNoiseSound("study", "学习", "NOISE", R.raw.study, "📚", Color(0xFF7986CB)),
            WhiteNoiseSound("white_noise", "白噪音", "NOISE", R.raw.white_noise, "📻", Color(0xFFB0BEC5)),
            
            // 场所声音
            WhiteNoiseSound("airport", "机场", "PLACES", R.raw.airport, "✈️", Color(0xFF42A5F5)),
            WhiteNoiseSound("cafe", "咖啡厅", "PLACES", R.raw.cafe, "☕", Color(0xFFA1887F)),
            WhiteNoiseSound("carousel", "旋转木马", "PLACES", R.raw.carousel, "🎠", Color(0xFFFF80AB)),
            WhiteNoiseSound("church", "教堂", "PLACES", R.raw.church, "⛪", Color(0xFF9E9E9E)),
            WhiteNoiseSound("construction_site", "建筑工地", "PLACES", R.raw.construction_site, "🏗️", Color(0xFFFF9800)),
            WhiteNoiseSound("crowded_bar", "拥挤酒吧", "PLACES", R.raw.crowded_bar, "🍺", Color(0xFFFFB74D)),
            WhiteNoiseSound("kitchen", "厨房", "PLACES", R.raw.kitchen, "🍳", Color(0xFFFFCA28)),
            WhiteNoiseSound("laboratory", "实验室", "PLACES", R.raw.laboratory, "🧪", Color(0xFF4FC3F7)),
            WhiteNoiseSound("laundry_room", "洗衣房", "PLACES", R.raw.laundry_room, "🧺", Color(0xFF90CAF9)),
            WhiteNoiseSound("library", "图书馆", "PLACES", R.raw.library, "📚", Color(0xFFBCAAA4)),
            WhiteNoiseSound("night_village", "夜晚村庄", "PLACES", R.raw.night_village, "🌙", Color(0xFF5C6BC0)),
            WhiteNoiseSound("office", "办公室", "PLACES", R.raw.office, "💼", Color(0xFF78909C)),
            WhiteNoiseSound("restaurant", "餐厅", "PLACES", R.raw.restaurant, "🍽️", Color(0xFFFF7043)),
            WhiteNoiseSound("subway_station", "地铁站", "PLACES", R.raw.subway_station, "🚇", Color(0xFF757575)),
            WhiteNoiseSound("supermarket", "超市", "PLACES", R.raw.supermarket, "🛒", Color(0xFF66BB6A)),
            WhiteNoiseSound("temple", "寺庙", "PLACES", R.raw.temple, "🛕", Color(0xFFFFB74D)),
            WhiteNoiseSound("underwater", "水下", "PLACES", R.raw.underwater, "🤿", Color(0xFF4FC3F7)),
            
            // 雨声
            WhiteNoiseSound("drizzle", "毛毛雨", "RAIN", R.raw.drizzle, "🌦️", Color(0xFF90CAF9)),
            WhiteNoiseSound("heavy_rain", "大雨", "RAIN", R.raw.heavy_rain, "🌧️", Color(0xFF42A5F5)),
            WhiteNoiseSound("heavy_rain_on_glass", "玻璃雨声", "RAIN", R.raw.heavy_rain_on_glass, "🪟", Color(0xFF64B5F6)),
            WhiteNoiseSound("light_rain", "小雨", "RAIN", R.raw.light_rain, "🌧️", Color(0xFF81D4FA)),
            WhiteNoiseSound("rain", "雨声", "RAIN", R.raw.rain, "🌧️", Color(0xFF64B5F6)),
            WhiteNoiseSound("rain_on_car_roof", "车顶雨声", "RAIN", R.raw.rain_on_car_roof, "🚗", Color(0xFF64B5F6)),
            WhiteNoiseSound("rain_on_eaves", "屋檐雨声", "RAIN", R.raw.rain_on_eaves, "🏠", Color(0xFF64B5F6)),
            WhiteNoiseSound("rain_on_empty_street", "空街雨声", "RAIN", R.raw.rain_on_empty_street, "🛣️", Color(0xFF64B5F6)),
            WhiteNoiseSound("rain_on_leaves", "树叶雨声", "RAIN", R.raw.rain_on_leaves, "🍃", Color(0xFF66BB6A)),
            WhiteNoiseSound("rain_on_raincoat", "雨衣雨声", "RAIN", R.raw.rain_on_raincoat, "🧥", Color(0xFF64B5F6)),
            WhiteNoiseSound("rain_on_tent", "帐篷雨声", "RAIN", R.raw.rain_on_tent, "⛺", Color(0xFF64B5F6)),
            WhiteNoiseSound("rain_on_umbrella", "雨伞雨声", "RAIN", R.raw.rain_on_umbrella, "☂️", Color(0xFF64B5F6)),
            WhiteNoiseSound("rain_on_windowsill", "窗台雨声", "RAIN", R.raw.rain_on_windowsill, "🪟", Color(0xFF64B5F6)),
            WhiteNoiseSound("rain_on_wooden_house", "木屋雨声", "RAIN", R.raw.rain_on_wooden_house, "🏡", Color(0xFF8D6E63)),
            WhiteNoiseSound("rain_while_driving", "驾驶雨声", "RAIN", R.raw.rain_while_driving, "🚙", Color(0xFF64B5F6)),
            WhiteNoiseSound("thunder", "雷声", "RAIN", R.raw.thunder, "⚡", Color(0xFF9575CD)),
            WhiteNoiseSound("thunderstorm", "雷暴", "RAIN", R.raw.thunderstorm, "⛈️", Color(0xFF5C6BC0)),
            
            // 物品声音
            WhiteNoiseSound("boiling_water", "沸水", "THINGS", R.raw.boiling_water, "♨️", Color(0xFFFF7043)),
            WhiteNoiseSound("bubbles", "气泡", "THINGS", R.raw.bubbles, "🫧", Color(0xFF4FC3F7)),
            WhiteNoiseSound("ceiling_fan", "吊扇", "THINGS", R.raw.ceiling_fan, "🌀", Color(0xFF90CAF9)),
            WhiteNoiseSound("clock", "时钟", "THINGS", R.raw.clock, "⏰", Color(0xFF757575)),
            WhiteNoiseSound("dryer", "烘干机", "THINGS", R.raw.dryer, "🌀", Color(0xFF90CAF9)),
            WhiteNoiseSound("ear_cleaning_1", "掏耳朵1", "THINGS", R.raw.ear_cleaning_1, "👂", Color(0xFFFFAB91)),
            WhiteNoiseSound("ear_cleaning_2", "掏耳朵2", "THINGS", R.raw.ear_cleaning_2, "👂", Color(0xFFFFAB91)),
            WhiteNoiseSound("fire", "火焰", "THINGS", R.raw.fire, "🔥", Color(0xFFFF8A65)),
            WhiteNoiseSound("guitar", "吉他", "THINGS", R.raw.guitar, "🎸", Color(0xFF8D6E63)),
            WhiteNoiseSound("guzheng", "古筝", "THINGS", R.raw.guzheng, "🎵", Color(0xFFFFB74D)),
            WhiteNoiseSound("keyboard", "键盘", "THINGS", R.raw.keyboard, "⌨️", Color(0xFF78909C)),
            WhiteNoiseSound("light_piano", "轻钢琴", "THINGS", R.raw.light_piano, "🎹", Color(0xFF9575CD)),
            WhiteNoiseSound("morse_code", "摩斯密码", "THINGS", R.raw.morse_code, "📡", Color(0xFF757575)),
            WhiteNoiseSound("paper", "纸张", "THINGS", R.raw.paper, "📄", Color(0xFFEEEEEE)),
            WhiteNoiseSound("singing_bowl", "颂钵", "THINGS", R.raw.singing_bowl, "🔔", Color(0xFFFFB74D)),
            WhiteNoiseSound("slide_projector", "幻灯机", "THINGS", R.raw.slide_projector, "📽️", Color(0xFF757575)),
            WhiteNoiseSound("tuning_radio", "调频收音机", "THINGS", R.raw.tuning_radio, "📻", Color(0xFF9E9E9E)),
            WhiteNoiseSound("typewriter", "打字机", "THINGS", R.raw.typewriter, "⌨️", Color(0xFF757575)),
            WhiteNoiseSound("vinyl_effect", "黑胶唱片", "THINGS", R.raw.vinyl_effect, "💿", Color(0xFF424242)),
            WhiteNoiseSound("washing_machine", "洗衣机", "THINGS", R.raw.washing_machine, "🌀", Color(0xFF90CAF9)),
            WhiteNoiseSound("wind_chimes", "风铃", "THINGS", R.raw.wind_chimes, "🎐", Color(0xFF81D4FA)),
            WhiteNoiseSound("windshield_wipers", "雨刷", "THINGS", R.raw.windshield_wipers, "🚗", Color(0xFF78909C)),
            
            // 交通工具
            WhiteNoiseSound("airplane", "飞机", "TRANSPORT", R.raw.airplane, "✈️", Color(0xFF42A5F5)),
            WhiteNoiseSound("inside_a_train", "火车内部", "TRANSPORT", R.raw.inside_a_train, "🚆", Color(0xFF757575)),
            WhiteNoiseSound("ocean", "海洋", "TRANSPORT", R.raw.ocean, "🌊", Color(0xFF4FC3F7)),
            WhiteNoiseSound("rowing_boat", "划船", "TRANSPORT", R.raw.rowing_boat, "🚣", Color(0xFF4FC3F7)),
            WhiteNoiseSound("sailboat", "帆船", "TRANSPORT", R.raw.sailboat, "⛵", Color(0xFF42A5F5)),
            WhiteNoiseSound("submarine", "潜艇", "TRANSPORT", R.raw.submarine, "🚢", Color(0xFF546E7A)),
            WhiteNoiseSound("train", "火车", "TRANSPORT", R.raw.train, "🚂", Color(0xFF757575)),
            
            // 城市声音
            WhiteNoiseSound("ambulance_siren", "救护车", "URBAN", R.raw.ambulance_siren, "🚑", Color(0xFFEF5350)),
            WhiteNoiseSound("busy_street", "繁忙街道", "URBAN", R.raw.busy_street, "🏙️", Color(0xFF78909C)),
            WhiteNoiseSound("crowd", "人群", "URBAN", R.raw.crowd, "👥", Color(0xFF9E9E9E)),
            WhiteNoiseSound("fireworks", "烟花", "URBAN", R.raw.fireworks, "🎆", Color(0xFFFF4081)),
            WhiteNoiseSound("highway", "高速公路", "URBAN", R.raw.highway, "🛣️", Color(0xFF757575)),
            WhiteNoiseSound("road", "道路", "URBAN", R.raw.road, "🛤️", Color(0xFF9E9E9E)),
            WhiteNoiseSound("traffic", "交通", "URBAN", R.raw.traffic, "🚦", Color(0xFF78909C))
        )
    }
}

