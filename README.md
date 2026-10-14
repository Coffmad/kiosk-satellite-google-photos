# Google Photos Screensaver for Kiosk Satellite

A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that turns a Google Photos shared album into a screensaver. Paste the album's share link, pick **Google Photos** as the screensaver mode, and photos stream from the album in random order with a smooth crossfade.

- Works with any album shared by link. No Google sign-in or API key.
- Shuffle shows every photo once before repeating, or play in album order.
- Mix several albums by pasting more than one link.
- Separate fit settings for horizontal and vertical photos: fill the screen, or show the whole photo over a blurred copy of itself or a plain color.
- A crop limit, so panoramas and portraits are never cropped beyond what you allow, and a choice of which part stays in view.
- Adjustable time per photo, crossfade duration and optional slow zoom (Ken Burns) effect.
- Optional date-taken caption.
- Photos download at your screen's resolution, one ahead, so each change is instant.
- Picks up new photos added to the album every few hours.
- All stock screensaver settings still apply: idle timeout, schedules, brightness, motion wake, widgets, At a Glance, pixel shift and Now Playing.

## Setup

1. In Google Photos, open the album and choose **Share > Create link** (or **Copy link** if it is already shared). The link looks like `https://photos.app.goo.gl/AbCdEf123`.
2. In Kiosk Satellite, open **Settings > Plugin Manager**, turn on **Enable Plugins**, choose **Add plugin**, paste `https://github.com/Coffmad/kiosk-satellite-google-photos` and choose **Preview**. Review it and choose **Trust and install**.
3. Enable **Google Photos Screensaver** from its entry row and open its subpage.
4. Paste the link into **Shared album link**.
5. Open **Screensaver > Screensaver mode** and choose **Google Photos (Google Photos Screensaver)**. You can also choose it in a screensaver schedule entry.

The plugin subpage shows how many photos were found, or why an album could not be read.

## Settings

**Album**

| Setting | What it does |
| --- | --- |
| Shared album link | One or more share links, separated by spaces |
| Check album for new photos every | How often the album list is downloaded again, 1 to 48 hours |

**Timing**

| Setting | What it does |
| --- | --- |
| Time per photo | 5 seconds to 10 minutes |
| Order | **Shuffle** shows every photo once before repeating. **Album order** follows the album |
| Transition | **Crossfade** or **Cut**. Cut allows sharper photos, see below |
| Crossfade duration | 0.5 to 5 seconds |
| Slow zoom | Slowly zooms each photo while it shows. Turn it off if older tablets stutter |
| Zoom amount | 5% to 30% over the photo's time on screen |

**Photo fit and cropping**

| Setting | What it does |
| --- | --- |
| Horizontal photos | **Fill screen (crop)**, **Whole photo, blurred background** or **Whole photo, plain background**. Default Fill screen |
| Vertical photos | The same choices for portrait photos. Default blurred background |
| Crop at most | With Fill screen, a photo that would lose more than this share of its picture shows whole on a blurred background instead. Default 30%, which fills the screen with ordinary 4:3 and 3:2 photos but keeps panoramas whole. 100% always crops |
| Keep in view when cropping | **Center**, **Upper part** or **Top**. Upper part keeps heads in frame when a tall photo fills a wide screen |
| Plain background color | Color around photos set to plain background |

**Display**

| Setting | What it does |
| --- | --- |
| Show date taken | **Off**, **Month and year** or **Full date**, in the bottom right corner |
| Maximum photo size | Caps the download size at 1280, 1920 or 2560 pixels on the long side |
| When a photo is too large | **Reduce resolution (keep quality)** or **Reduce quality (keep resolution)**, see below |

### Actions

**Next photo** and **Reload albums** can be assigned to gestures, the kiosk drawer or Home Assistant buttons from the plugin subpage.

## How it works and its limits

Kiosk Satellite renders plugin screensavers in a locked-down page that cannot reach the network. The plugin therefore downloads each photo itself, sized for your screen by Google's image server, and hands it to the screensaver page embedded in the document. Each new page opens on the previous photo and fades to the next one, and Kiosk Satellite keeps the old page on screen until the new one has painted, so there is no flash of black between photos.

- **Image size.** A screensaver page can be at most 512 KB, and a crossfade page holds two photos, so each photo gets about 250 KB. Photos are downloaded at Google's normal quality first. A highly detailed photo that does not fit is requested again, smaller by default, or at stronger compression and full resolution when **When a photo is too large** is set to reduce quality. In testing with a large album of 12-megapixel phone photos at 1920 px, roughly a third needed a retry with Crossfade and about a sixth with Cut. **Cut** holds only one photo per page and so allows roughly twice the detail.
- **Videos** in the album show as a still frame.
- **Unofficial access.** Google no longer offers an API that can read albums an app did not create, so the plugin reads the album's public share page, the same page a browser shows. If Google changes that page the plugin may stop finding photos until it is updated. Only albums shared by link work. Private album links (`photos.google.com/album/…`) cannot be read.
- **Data use.** Photos download only while this screensaver mode is on screen and the display is on, plus one photo ahead. At the default 30 seconds per photo that is about 120 photos an hour, typically 150 to 250 KB each.
- **Privacy.** Anyone with a shared album link can view the album. The plugin sends requests only to `photos.app.goo.gl`, `photos.google.com` and `lh3.googleusercontent.com`.

## Build and test

Install Python 3, JDK 17 or newer and an Android SDK with a platform and build-tools. Set `ANDROID_HOME` and `JAVA_HOME` as needed.

```sh
python3 tools/test.py
python3 tools/build.py
```

The build writes `dist/google-photos-screensaver-<version>.zip`. For local testing, install it with **Plugin Manager > Developer Tools > Install from ZIP**.

## Publish

Commit the source, then publish a GitHub release tagged with the version, such as `v1.0.0`. The included workflow tests and builds the plugin and attaches the manifest, ZIP and checksum that Kiosk Satellite requires. Share the repository URL so others can install it with **Add plugin**.

The SDK sources in `sdk/` and the build tools in `tools/` come from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world).

## License

[Apache-2.0](LICENSE). Kiosk Satellite has its own application license. Google Photos is a trademark of Google LLC. This plugin is not affiliated with or endorsed by Google.
