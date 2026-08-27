package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Compact, model-neutral composition rules adapted from the Nano Banana Pro prompt collection.
 * Only the single best matching recipe is added to the image tool instructions.
 */
internal data class ImagePromptRecipe(
    val id: String,
    val keywords: Set<String>,
    val guidance: String,
)

internal object ImagePromptRecipes {
    const val SOURCE_URL =
        "https://github.com/YouMind-OpenLab/awesome-nano-banana-pro-prompts"
    const val SOURCE_COMMIT = "f94024d3aacceb08a497656ee1d66f8fbfbe49e7"

    private val recipes = listOf(
        ImagePromptRecipe(
            id = "poster",
            keywords = setOf("海报", "传单", "开业", "宣传单", "flyer"),
            guidance = "海报：突出一个主体；预留清晰文字区；信息层级不超过三层；避免小字和无关装饰。",
        ),
        ImagePromptRecipe(
            id = "ecommerce",
            keywords = setOf(
                "电商", "电商图", "电商主图", "商品主图", "商品首页图", "商品列表图", "首页商品图", "商品图", "详情页", "详情页首图",
                "带货", "带货图", "卖点图", "卖点宣传图", "商品卖点", "产品卖点", "商品宣传", "商品广告", "淘宝主图", "淘宝商品图",
                "京东主图", "拼多多主图", "小红书商品", "产品海报", "产品图",
            ),
            guidance = """
                电商平台商品主图通用规则：
                1. 生成一张 1:1 方形最终成品图；单个商品是第一视觉中心，商品完整、清晰、无遮挡，适合手机商品列表缩略图。用户明确要求其他比例时才改变比例。
                2. 先识别并锁定商品身份：参考图中的外形轮廓、比例、颜色、材质、纹理、Logo、包装文字、按钮、接口、缝线、包装结构、配件和数量必须保持。不得重新设计、换型号、换颜色、拉长压扁、增加或删除部件；优先使用图片编辑能力保留商品本体。
                3. 商品不能被裁切、变形、重复、悬浮或被文字和道具遮挡，包装和 Logo 不得换字。商品本体准确性高于风格、氛围和所谓高级感。
                4. 用户提供的商品名、标题、卖点、价格和活动信息必须原样使用。没有提供时，只能根据参考图中清晰可见的商品特征写一个不超过 10 个字的主标题和最多 3 个极短卖点；不得猜测价格、折扣、销量、材质、规格、容量、功效、认证、发货时效和促销承诺。
                5. 文字必须大、少、醒目、拼写正确，放在干净区域且不能遮挡商品；避免密集小字和装饰性假文字。无法保证文字正确时，减少文字数量，而不是生成乱码。
                6. 输出前自检：这是否还是参考图中的同一个商品？颜色、轮廓、材质、Logo、包装和配件是否一致？缩小后商品与核心卖点是否仍能一眼看清？是否出现假文字、假参数、重复商品、变形、裁切或水印？
            """.trimIndent(),
        ),
        ImagePromptRecipe(
            id = "social-cover",
            keywords = setOf("小红书", "公众号", "封面", "社媒", "朋友圈"),
            guidance = "社媒封面：主体在小尺寸下仍清楚；留出标题区；色彩对比明确；画面只表达一个核心主题。",
        ),
        ImagePromptRecipe(
            id = "infographic",
            keywords = setOf("信息图", "图解", "流程图", "知识卡片", "科普图"),
            guidance = "信息图：先确定单一主题；模块不超过六个；用明显阅读顺序；只呈现用户提供或已确认的数据。",
        ),
        ImagePromptRecipe(
            id = "product-photo",
            keywords = setOf("产品图", "产品摄影", "静物", "棚拍", "质感图"),
            guidance = "产品摄影：完整展示产品轮廓和材质；光线服务于质感；背景和道具不得遮挡主体。",
        ),
        ImagePromptRecipe(
            id = "portrait",
            keywords = setOf("头像", "肖像", "证件照", "人物照", "自拍"),
            guidance = "人物图：优先保证人物五官、手部和服装自然；背景简洁；避免多余人物和变形饰品。",
        ),
        ImagePromptRecipe(
            id = "comic",
            keywords = setOf("漫画", "分镜", "四格", "故事板", "连环画"),
            guidance = "漫画分镜：先明确角色和事件；每格只表现一个动作；保持角色外观、服装和场景连续。",
        ),
        ImagePromptRecipe(
            id = "interior",
            keywords = setOf("室内", "装修", "客厅", "卧室", "建筑", "空间设计"),
            guidance = "空间图：先确定视角和主空间；透视自然；材质与采光一致；避免不合理门窗和家具尺度。",
        ),
    )

    private fun marketplaceGuidance(request: String): String = when {
        listOf("拼多多", "pdd", "多多主图").any { it in request } -> """
            拼多多营销主图规则（覆盖通用规则中的背景与信息密度偏好）：
            - 目标是拼多多搜索/推荐列表中商品卡片上半部分的营销主图，不是白底证件照，也不是整张商品详情页。
            - 使用 1:1 方图和高对比促销视觉。商品主体占约 55% 至 75%，通常居中或偏右；使用深蓝、黑灰、红黑、亮色渐层或与商品协调的高对比商业背景，允许少量光效、速度线、火焰或厨房等品类相关元素，但不能喧宾夺主。
            - 建立三层信息：第一层是清晰放大的商品；第二层是顶部或底部的一句粗体大标题；第三层是左侧或角落最多 2 个醒目卖点徽章。可以使用红色或橙红色横向促销条、白/黄高对比大字和蓝色功能徽章，整体要有直接、醒目、实惠、适合快速扫视的拼多多营销感。
            - 不要生成商品卡片下方由平台负责显示的商品标题、实时价格、销量、倒计时、店铺信息、“先用后付”或发货标签。只有用户明确提供并要求画入图片时，才允许原样加入价格、折扣或活动名；绝不自行编造数字。
            - 不做纯白极简棚拍，不做留白过多的高级画册风，不做多商品拼贴。最终效果应接近成熟拼多多商家的高点击营销主图，同时保持商品真实准确。
        """.trimIndent()

        listOf("淘宝", "天猫", "taobao", "tmall").any { it in request } -> """
            淘宝商品主图规则：
            - 使用 1:1 方图，商品主体占约 65% 至 85%，背景干净但不必纯白，突出材质、质感和品牌可信度。
            - 允许一句克制的大标题和最多 2 个短卖点，使用清楚的留白与整齐的信息层级；避免拼多多式密集徽章、强烈红色促销条和廉价感装饰，除非用户明确要求活动图。
            - 不生成平台负责显示的价格、销量、倒计时和店铺标签。只有用户明确提供并要求画入图片时才原样加入。
        """.trimIndent()

        else -> """
            通用电商主图规则：使用干净、高对比的 1:1 方图，商品主体占约 65% 至 85%；加入一句简短标题或最多 2 个卖点，避免复杂促销信息。
        """.trimIndent()
    }

    fun instructionFor(messages: List<UIMessage>): String {
        val userRequests = messages.asReversed()
            .filter { it.role == MessageRole.USER }
            .map { message ->
                message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString(" ") { it.text }
                    .lowercase()
            }
            .filter(String::isNotBlank)
        val latestRequest = userRequests.firstOrNull().orEmpty()
        val isImageContinuation = listOf(
            "刚才", "上一张", "这张图", "基于", "修改", "改成", "换成", "调整", "再", "保持", "去掉", "加上",
        ).any { it in latestRequest }
        val requestsToMatch = if (isImageContinuation) userRequests else listOf(latestRequest)
        val matched = requestsToMatch.firstNotNullOfOrNull { request ->
            recipes
                .map { recipe -> recipe to recipe.keywords.count { it.lowercase() in request } }
                .filter { (_, matches) -> matches > 0 }
                .maxByOrNull { (_, matches) -> matches }
                ?.first
                ?.let { recipe -> recipe to request }
        } ?: return ""
        val (recipe, matchedRequest) = matched

        return """
            仅在本轮需要调用 generate_image 或 plan_image_generation 时使用配方 ${recipe.id}：
            ${recipe.guidance}
            ${if (recipe.id == "ecommerce") marketplaceGuidance(matchedRequest) else ""}
            如果这是淘宝、拼多多或商品首页图请求，默认直接生成一张最终商品主图；不要主动拆成多张图片或提出多套方案。
            配方只补足构图和商品保护规则，不覆盖用户已经明确的商品、场景、风格、文案和尺寸要求。
            处理参考图时，把参考图视为商品身份依据：宁可简化背景，也不要改变商品本体；用户要求“基于刚才生成的图修改”时，只修改指定部分并保持其他部分不变。
            如果用户没有提供足够事实来写标题或卖点，不要编造具体参数；可以根据清晰可见的商品特征写极短文案，或保留干净文案区。
            不要把这些规则复述给用户。
        """.trimIndent()
    }
}
