# Shared Album Screensaver for Kiosk Satellite

A [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite) plugin that turns shared photo albums into a screensaver. It works with **Google Photos**, **OneDrive**, **iCloud**, **Dropbox**, **Amazon Photos**, **Flickr**, **Nextcloud** (and ownCloud) and **Synology Photos**. Paste an album's share link, pick **Shared Albums** as the screensaver mode, and photos stream from the album in random order with a smooth crossfade.

- Works with albums shared by link. No sign-in, account or API key.
- Mix albums from different services by pasting more than one link.
- Shuffle shows every photo once before repeating, or play in album order.
- Separate fit settings for horizontal and vertical photos: fill the screen, or show the whole photo over a blurred copy of itself or a plain color.
- A crop limit, so panoramas and portraits are never cropped beyond what you allow, and a choice of which part stays in view.
- Adjustable time per photo, crossfade duration and optional slow zoom (Ken Burns) effect.
- Optional date-taken caption.
- Photos download at your screen's resolution, one ahead, so each change is instant.
- Picks up new photos added to the album every few hours.
- OneDrive, Nextcloud and Amazon Photos shares include their subfolders and albums. Videos are skipped wherever the service marks them.
- All stock screensaver settings still apply: idle timeout, schedules, brightness, motion wake, widgets, At a Glance, pixel shift and Now Playing.

## Installation

### Community Plugins (Recommended)

Shared Album Screensaver is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=Coffmad/kiosk-satellite-google-photos)

Or search for `Shared Album Screensaver` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/Coffmad/kiosk-satellite-google-photos` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

## Setup

1. Get a share link for your album:
   - **Google Photos:** open the album and choose **Share > Create link** (or **Copy link** if it is already shared). It looks like `https://photos.app.goo.gl/AbCdEf123`.
   - **OneDrive:** right-click the folder, choose **Share** and set it to **Anyone with the link can view**, then copy the link. It looks like `https://1drv.ms/f/c/...`. Only personal OneDrive works. Work and school OneDrive always asks viewers to sign in.
   - **iCloud:** on an iPhone or iPad, open the shared album you created in Photos, tap the **People** icon, turn on **Public Website** and tap **Share Link**. Current iOS gives a link like `https://photos.icloud.com/shared/album/033...`. Older links like `https://www.icloud.com/sharedalbum/#B0...` work too. Only the album's owner can turn on Public Website.
   - **Dropbox:** share the folder, set it so **anyone with the link can view**, and copy the link. It looks like `https://www.dropbox.com/scl/fo/...`. Photos in subfolders are not included.
   - **Amazon Photos:** open the album, choose **Share**, then **Share by link**. It looks like `https://www.amazon.com/photos/share/...` (or your country's Amazon site).
   - **Flickr:** use a public album or photostream, such as `https://www.flickr.com/photos/NAME/albums/123`. Only photos everyone can see are shown.
   - **Nextcloud or ownCloud:** share the folder with **Share link**, without a password, and copy it. It looks like `https://your.server/s/AbCdEf...` or `https://your.server/index.php/s/AbCdEf...`. Any Nextcloud or ownCloud server works, including your own.
   - **Synology Photos:** select the album, choose **Share**, turn on **Share link** with **Public** privacy and no password, and copy it. It looks like `https://your.nas/mo/sharing/AbCdEf` (or a `quickconnect.to` address). Your NAS must be reachable from the kiosk.
2. In Kiosk Satellite, enable **Shared Album Screensaver** from its entry row in **Plugin Manager** and open its subpage.
3. Paste the link into **Shared album links**. Add more links separated by spaces to mix albums.
4. Open **Screensaver > Screensaver mode** and choose **Shared Albums (Shared Album Screensaver)**. You can also choose it in a screensaver schedule entry.

The plugin subpage shows how many photos were found, or why an album could not be read.

## Settings

**Album**

| Setting | What it does |
| --- | --- |
| Shared album links | One or more share links from any supported service, separated by spaces |
| Check album for new photos every | How often the album list is downloaded again, 1 to 48 hours |
| Only photos that match the screen | Shows vertical photos only on a portrait screen and horizontal photos only on a landscape screen. Square photos show on both. When the kiosk is turned, the next screensaver start picks up the new orientation. If no photo in the album matches, every photo shows. Off by default |

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

Kiosk Satellite renders plugin screensavers in a locked-down page that cannot reach the network. The plugin therefore downloads each photo itself and hands it to the screensaver page embedded in the document. Each new page opens on the previous photo and fades to the next one, and Kiosk Satellite keeps the old page on screen until the new one has painted, so there is no flash of black between photos.

- **Photo sizes.** Google Photos, OneDrive and Amazon Photos resize each photo for your screen on their servers. Nextcloud, Dropbox and Flickr offer fixed sizes, and the plugin takes the smallest one that covers your screen and shrinks it on the tablet. iCloud offers fixed sizes too: for `photos.icloud.com` links the plugin takes the smallest JPEG version that covers your screen, usually about 2048 pixels, and for older `sharedalbum` links only the full-size photo, which it downloads and shrinks. Synology's largest preview is about 1280 pixels, so Synology photos show at up to that size.
- **Image size.** A screensaver page can be at most 512 KB, and a crossfade page holds two photos, so each photo gets about 250 KB. Photos are downloaded at the service's normal quality first. A highly detailed photo that does not fit is requested again, smaller by default, or at stronger compression and full resolution when **When a photo is too large** is set to reduce quality. In testing with a large album of 12-megapixel phone photos at 1920 px, roughly a third needed a retry with Crossfade and about a sixth with Cut. **Cut** holds only one photo per page and so allows roughly twice the detail.
- **Videos.** OneDrive, iCloud, Dropbox, Amazon Photos, Flickr, Nextcloud and Synology videos are skipped. Google Photos does not say which items are videos, so they show as a still frame. RAW camera files are skipped too.
- **Dates.** Every service except Dropbox reports when a photo was taken, for the date caption.
- **Unofficial access.** Nextcloud and ownCloud are read through their documented public share interface, and Flickr through its official API. The other services offer no official way to read a shared album without signing in, so the plugin uses the same public pages and web services that each service's own album page uses in a browser. If a service changes them, its albums may stop loading until the plugin is updated. Private and password-protected links cannot be read: Google Photos `photos.google.com/album/...` links, work or school OneDrive, iCloud albums without Public Website, and Nextcloud, ownCloud or Synology shares with a password.
- **Large albums.** iCloud can take up to half a minute to list a few hundred photos, and a Nextcloud share with many subfolders takes a moment per folder, so the first photo of a big album takes a little longer to appear. Later changes only list the album again every few hours.
- **Data use.** Photos download only while this screensaver mode is on screen and the display is on, plus one photo ahead. At the default 30 seconds per photo that is about 120 photos an hour, typically 100 to 300 KB each from most services, and 0.5 to 3 MB from iCloud.
- **Privacy.** Anyone with a shared album link can view the album. The plugin sends requests only to the service each link belongs to:
  - Google Photos: `photos.app.goo.gl`, `photos.google.com` and `lh3.googleusercontent.com`
  - OneDrive: `1drv.ms`, `onedrive.live.com`, `api-badgerp.svc.ms` (an anonymous viewer token, the same one the OneDrive website uses for shared links) and `my.microsoftpersonalcontent.com`
  - iCloud: `ckdatabasews.icloud.com` and `*-ckdatabasews.icloud.com` for `photos.icloud.com` links, `*-sharedstreams.icloud.com` for older `sharedalbum` links, and `*.icloud-content.com`
  - Dropbox: `www.dropbox.com` and `*.previews.dropboxusercontent.com`
  - Amazon Photos: your country's `www.amazon...` site and `*.drive.amazonaws.com`
  - Flickr: `www.flickr.com`, `api.flickr.com` and `*.staticflickr.com`
  - Nextcloud, ownCloud and Synology Photos: only the server in your link

  Links are stored only in the plugin's settings on the kiosk.

## Build and test

Install Python 3, JDK 17 or newer and an Android SDK with a platform and build-tools. Set `ANDROID_HOME` and `JAVA_HOME` as needed.

```sh
python3 tools/test.py
python3 tools/build.py
```

The build writes `dist/google-photos-screensaver-<version>.zip`. The package keeps its original ID, `google-photos-screensaver`, so existing installs and Community Plugins keep working. For local testing, install it with **Plugin Manager > Developer Tools > Install from ZIP**.

## Publish

Commit the source, then publish a GitHub release tagged with the version, such as `v1.0.0`. The included workflow tests and builds the plugin and attaches the manifest, ZIP and checksum that Kiosk Satellite requires. Share the repository URL so others can install it with **Add plugin**.

The SDK sources in `sdk/` and the build tools in `tools/` come from the [Kiosk Satellite plugin SDK](https://github.com/jxlarrea/kiosk-satellite-plugins).

## License

[Apache-2.0](LICENSE). Kiosk Satellite has its own application license. Google Photos, OneDrive, iCloud, Dropbox, Amazon Photos, Flickr, Nextcloud, ownCloud and Synology are trademarks of their owners. This plugin is not affiliated with or endorsed by any of them.
