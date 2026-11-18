//! Placeholder tray icons: a filled circle per state, generated in memory (the real set is ticket DSI).

use aristotle_core::snapshot::TrayState;
use ksni::Icon;

const SIZE: i32 = 22;

/// design-notes.md tray colours, dark-panel variants: green `#5FD1AE`, amber `#F2B33D`, red `#FF6B5E`.
pub fn colour(state: TrayState) -> [u8; 3] {
    match state {
        TrayState::Green => [0x5F, 0xD1, 0xAE],
        TrayState::Amber => [0xF2, 0xB3, 0x3D],
        TrayState::Red => [0xFF, 0x6B, 0x5E],
    }
}

/// ARGB32, network byte order, as StatusNotifierItem wants it.
pub fn pixmap(state: TrayState) -> Icon {
    let [r, g, b] = colour(state);
    let centre = (SIZE as f32 - 1.0) / 2.0;
    let mut data = Vec::with_capacity((SIZE * SIZE * 4) as usize);
    for y in 0..SIZE {
        for x in 0..SIZE {
            let dist = ((x as f32 - centre).powi(2) + (y as f32 - centre).powi(2)).sqrt();
            let alpha = if dist <= centre - 1.0 { 0xFF } else { 0x00 };
            data.extend_from_slice(&[alpha, r, g, b]);
        }
    }
    Icon {
        width: SIZE,
        height: SIZE,
        data,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pixmap_is_22x22_argb_with_the_state_colour_in_the_middle() {
        for state in [TrayState::Green, TrayState::Amber, TrayState::Red] {
            let icon = pixmap(state);
            assert_eq!((icon.width, icon.height), (22, 22));
            assert_eq!(icon.data.len(), 22 * 22 * 4);
            let mid = (11 * 22 + 11) * 4;
            let [r, g, b] = colour(state);
            assert_eq!(&icon.data[mid..mid + 4], &[0xFF, r, g, b]);
            assert_eq!(icon.data[0], 0x00, "corner is transparent");
        }
    }

    #[test]
    fn the_three_states_have_distinct_colours() {
        let c = [TrayState::Green, TrayState::Amber, TrayState::Red].map(colour);
        assert!(c[0] != c[1] && c[1] != c[2] && c[0] != c[2]);
    }
}
