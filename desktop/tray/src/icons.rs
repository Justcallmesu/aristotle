//! Tray icons: PNGs rendered from `assets/*.svg` by `tools/make_icons.py`, embedded at build time and
//! converted to the ARGB32 pixmaps StatusNotifierItem wants. 16 px is the heavier head-and-hand variant.

use aristotle_core::state::TrayState;
use ksni::Icon;
use std::sync::OnceLock;

/// Sizes offered to the panel; it picks the closest.
pub const SIZES: [i32; 4] = [16, 22, 24, 32];

/// Single tint per state, each >= 3:1 against both Breeze panels (`#EFF0F1`, `#31363B`).
/// Kept in sync with the SVG sources (`tools/make_icons.py`); the tests check the pixmaps against it.
#[cfg(test)]
fn colour(state: TrayState) -> [u8; 3] {
    match state {
        TrayState::Green => [0x1E, 0x9C, 0x77],
        TrayState::Amber => [0xB8, 0x77, 0x00],
        TrayState::Red => [0xE5, 0x48, 0x3B],
    }
}

macro_rules! png {
    ($state:literal, $px:literal) => {
        include_bytes!(concat!("../assets/png/tray-", $state, "-", $px, ".png")) as &[u8]
    };
}

fn png_bytes(state: TrayState, size: i32) -> &'static [u8] {
    match (state, size) {
        (TrayState::Green, 16) => png!("green", "16"),
        (TrayState::Green, 22) => png!("green", "22"),
        (TrayState::Green, 24) => png!("green", "24"),
        (TrayState::Green, 32) => png!("green", "32"),
        (TrayState::Amber, 16) => png!("amber", "16"),
        (TrayState::Amber, 22) => png!("amber", "22"),
        (TrayState::Amber, 24) => png!("amber", "24"),
        (TrayState::Amber, 32) => png!("amber", "32"),
        (TrayState::Red, 16) => png!("red", "16"),
        (TrayState::Red, 22) => png!("red", "22"),
        (TrayState::Red, 24) => png!("red", "24"),
        (TrayState::Red, 32) => png!("red", "32"),
        _ => unreachable!("size is always one of SIZES"),
    }
}

/// Decodes an RGBA8 PNG into ARGB32, network byte order.
fn decode(bytes: &[u8]) -> Icon {
    let mut reader = png::Decoder::new(std::io::Cursor::new(bytes))
        .read_info()
        .expect("embedded PNG header");
    let mut buf = vec![0; reader.output_buffer_size().expect("embedded PNG size")];
    let info = reader.next_frame(&mut buf).expect("embedded PNG frame");
    assert_eq!(
        info.color_type,
        png::ColorType::Rgba,
        "embedded PNGs are RGBA"
    );
    assert_eq!(
        info.bit_depth,
        png::BitDepth::Eight,
        "embedded PNGs are 8 bit"
    );
    let data = buf[..info.buffer_size()]
        .as_chunks::<4>()
        .0
        .iter()
        .flat_map(|p| [p[3], p[0], p[1], p[2]])
        .collect();
    Icon {
        width: info.width as i32,
        height: info.height as i32,
        data,
    }
}

/// One pixmap per size in [`SIZES`], decoded on first use and kept for the life of the process.
pub fn pixmaps(state: TrayState) -> &'static [Icon] {
    static CACHE: [OnceLock<Vec<Icon>>; 3] = [OnceLock::new(), OnceLock::new(), OnceLock::new()];
    let slot = match state {
        TrayState::Green => 0,
        TrayState::Amber => 1,
        TrayState::Red => 2,
    };
    CACHE[slot].get_or_init(|| SIZES.iter().map(|&s| decode(png_bytes(state, s))).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    const STATES: [TrayState; 3] = [TrayState::Green, TrayState::Amber, TrayState::Red];

    /// Sum of per-pixel alpha differences, in whole pixels (anti-aliasing counts fractionally).
    fn differing(a: &Icon, b: &Icon) -> f32 {
        let alpha = |i: &Icon| {
            i.data
                .as_chunks::<4>()
                .0
                .iter()
                .map(|p| p[0] as f32)
                .collect::<Vec<_>>()
        };
        alpha(a)
            .iter()
            .zip(alpha(b))
            .map(|(x, y)| (x - y).abs())
            .sum::<f32>()
            / 255.0
    }

    fn ink(i: &Icon) -> f32 {
        i.data
            .as_chunks::<4>()
            .0
            .iter()
            .map(|p| p[0] as f32 / 255.0)
            .sum::<f32>()
            / (i.width * i.height) as f32
    }

    #[test]
    fn every_state_serves_every_declared_size_with_matching_data() {
        for state in STATES {
            let icons = pixmaps(state);
            assert_eq!(icons.len(), SIZES.len());
            for (icon, size) in icons.iter().zip(SIZES) {
                assert_eq!((icon.width, icon.height), (size, size));
                assert_eq!(icon.data.len(), (size * size * 4) as usize);
                assert!(ink(icon) > 0.2, "{state:?} {size} is not blank");
                assert_eq!(icon.data[0], 0, "{state:?} {size}: top-left is transparent");
            }
        }
    }

    #[test]
    fn the_pixmaps_are_decoded_once_per_state() {
        for state in STATES {
            assert!(
                std::ptr::eq(pixmaps(state).as_ptr(), pixmaps(state).as_ptr()),
                "{state:?} is decoded on every call"
            );
        }
    }

    #[test]
    fn opaque_pixels_carry_the_state_colour() {
        for state in STATES {
            let [r, g, b] = colour(state);
            for icon in pixmaps(state) {
                let solid = icon
                    .data
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .find(|p| p[0] == 0xFF)
                    .expect("a solid pixel");
                assert_eq!(&solid[1..], &[r, g, b], "{state:?} {}", icon.width);
            }
        }
    }

    #[test]
    fn the_three_states_differ_in_shape_not_only_colour() {
        for (i, size) in SIZES.iter().enumerate() {
            let [g, a, r] = STATES.map(|s| pixmaps(s)[i].clone());
            for (x, y, n) in [
                (&g, &a, "green/amber"),
                (&a, &r, "amber/red"),
                (&g, &r, "green/red"),
            ] {
                // circle vs square is the closest pair; anti-alias noise alone stays far below this
                assert!(
                    differing(x, y) >= 3.0,
                    "{n} at {size} px share their alpha mask"
                );
            }
        }
    }

    #[test]
    fn the_16_px_variant_is_solid() {
        // heavier head-and-hand variant: about a third of the square is ink, not a thin line drawing
        for state in STATES {
            let v = ink(&pixmaps(state)[0]);
            assert!(v > 0.30, "{state:?}: 16 px ink fraction {v}");
        }
    }
}
