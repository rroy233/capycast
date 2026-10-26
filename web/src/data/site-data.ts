export const siteLinks = {
  repository: "https://github.com/rroy233/capycast",
  latestRelease: "https://github.com/rroy233/capycast/releases/latest",
  releases: "https://github.com/rroy233/capycast/releases",
  license: "https://github.com/rroy233/capycast/blob/main/LICENSE",
  issues: "https://github.com/rroy233/capycast/issues/new/choose",
} as const;

export const navigationItems = [
  { label: "首页", href: "#top", sectionId: "top" },
  { label: "功能", href: "#features", sectionId: "features" },
  { label: "截图", href: "#screenshots", sectionId: "screenshots" },
  { label: "下载", href: "#download", sectionId: "download" },
  { label: "常见问题", href: "#faq", sectionId: "faq" },
] as const;

export const featureItems = [
  {
    icon: "privacy",
    iconClass: "icon-blue",
    title: "保护账号隐私",
    description:
      "在家庭、宿舍或教室共用电视，也不会让别人翻看你的个人推荐和订阅。本机投屏记录可以关闭。",
  },
  {
    icon: "route",
    iconClass: "icon-violet",
    title: "减少播放卡顿",
    description:
      "家里网速正常，投屏却一直缓冲？应用会自动优选官方 CDN，减少劣质 PCDN 线路造成的卡顿。",
  },
  {
    icon: "display",
    iconClass: "icon-teal",
    title: "支持大会员画质",
    description:
      "已有的 B站大会员，在电视上也能用来观看高画质视频。支持账号权益内的最高画质，具体取决于片源和设备。",
  },
  {
    icon: "danmaku",
    iconClass: "icon-slate",
    title: "投屏也能看弹幕",
    description:
      "在电视上看视频，也能看弹幕。弹幕随播放进度同步，字号、透明度和显示区域都能调整。",
  },
] as const;

export const heroSellingPointItems = [
  {
    icon: "privacy",
    iconClass: "icon-blue",
    title: "保护账号隐私",
    description: "共用电视也不暴露推荐和订阅",
  },
  {
    icon: "history",
    iconClass: "icon-violet",
    title: "可关闭播放记录",
    description: "本机记录和账号同步可分别关闭",
  },
  {
    icon: "display",
    iconClass: "icon-teal",
    title: "支持大会员画质",
    description: "使用账号权益内的高画质和音轨",
  },
  {
    icon: "route",
    iconClass: "icon-violet",
    title: "自动优选官方 CDN",
    description: "减少劣质 PCDN 线路造成的卡顿",
  },
  {
    icon: "danmaku",
    iconClass: "icon-slate",
    title: "投屏也能看弹幕",
    description: "弹幕随播放进度同步显示",
  },
] as const;

export const screenshotItems = [
  {
    src: "https://raw.githubusercontent.com/rroy233/capycast/main/docs/images/screenshot-home.webp",
    fallback: "/assets/screenshot-home.webp",
    alt: "CapyCast 主页界面",
    caption: "投屏首页",
    summary: "不展示个人推荐和订阅",
  },
  {
    src: "https://raw.githubusercontent.com/rroy233/capycast/main/docs/images/screenshot-playing.webp",
    fallback: "/assets/screenshot-playing.webp",
    alt: "CapyCast 视频播放界面",
    caption: "播放界面",
    summary: "支持弹幕和遥控器操作",
  },
  {
    src: "https://raw.githubusercontent.com/rroy233/capycast/main/docs/images/screenshot-resolution.webp",
    fallback: "/assets/screenshot-resolution.webp",
    alt: "CapyCast 画质选择界面",
    caption: "画质设置",
    summary: "查看账号可用的画质和音轨",
  },
] as const;

export const setupSteps = [
  {
    number: "01",
    title: "在电视安装 CapyCast",
    description: "在电视上安装并打开应用。想使用大会员画质，可扫码登录自己的 B站账号。",
  },
  {
    number: "02",
    title: "手机和电视连接同一网络",
    description: "将手机和电视连接到同一路由器，电视也可以使用网线连接。",
  },
  {
    number: "03",
    title: "在 B站 App 点击投屏",
    description: "打开视频，点击「投屏」，选择电视画面上显示的设备名称。",
  },
] as const;

export const supportedPlatforms = [
  "Android TV",
  "Google TV",
  "Android 电视盒子",
  "Android 5.0+",
] as const;

export const faqItems = [
  {
    question: "在共用电视上登录，别人能看到哪些内容？",
    answer:
      "CapyCast 没有个人推荐、关注或订阅页面，也不会展示你在其他设备上的观看历史。电视上只显示本机最近投屏，方便继续播放；这项记录可以在设置中关闭。",
  },
  {
    question: "如何关闭播放记录？",
    answer:
      "在「设置 → 播放偏好」中，关闭「记忆播放内容」会清除本机投屏记录，并停止保存后续记录。关闭「同步播放记录到 B 站」可停止向账号同步投屏历史和进度。两项设置默认开启，可以分别关闭。",
  },
  {
    question: "已有 B站大会员，电视上能用哪些画质？",
    answer:
      "登录已有的大会员账号后，CapyCast 会提供该账号可用的画质和音轨，包括 4K、8K、HDR、Dolby 画质及 Dolby Atmos 音轨。实际能否播放取决于片源和电视的解码能力。CapyCast 本身免费，不收取会员费用。未登录也可以投屏，但可用画质会受到限制。",
  },
  {
    question: "家里网速正常，为什么投屏还是卡？",
    answer:
      "卡顿也可能来自视频的播放线路，部分 PCDN 线路即使在本地网络正常时也会缓冲。CapyCast 会检测候选线路，优先使用可用的官方 CDN，必要时尝试备用线路。线路优选可以减少这类卡顿，实际播放仍受网络和设备性能影响。",
  },
  {
    question: "哪些电视可以安装？",
    answer:
      "支持 Android TV、Google TV 和兼容的 Android 电视盒子，要求 Android 5.0 及以上，处理器为 arm64-v8a 或 armeabi-v7a。下载页的单个 APK 已包含这两种架构。暂不适配手机和平板。",
  },
  {
    question: "手机为什么找不到电视？",
    answer:
      "先确认电视上的 CapyCast 已打开，手机和电视连接到同一局域网。如果仍然找不到，请检查路由器的 AP 隔离或访客网络隔离设置，这些功能可能阻止手机发现电视。",
  },
  {
    question: "遇到播放问题，如何反馈？",
    answer:
      "请在 GitHub 提交 Issue，附上应用版本、电视型号、Android 版本、复现步骤，以及电视诊断地址提供的播放报告。提交前请检查并确认没有账号凭据等敏感信息。",
  },
] as const;
