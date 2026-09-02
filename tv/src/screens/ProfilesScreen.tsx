import { useState } from 'react';
import { ScrollView, Text, TextInput, View } from 'react-native';
import MaterialCommunityIcons from '@expo/vector-icons/MaterialCommunityIcons';
import { Focusable } from '../components/Focusable';
import { Icon } from '../components/Icon';
import { TvButton } from '../components/Ui';
import { AVATARS, COLOURS, GUEST, isGuest, type Profile } from '../lib/profiles';
import {
  font,
  radius,
  SAFE,
  space,
  type,
  useColors,
  useThemedStyles,
} from '../theme';

const TILE = 148;

/** The face on a tile: the chosen icon, or the initial for profiles made
 *  before icons existed. */
function Face({ profile, size }: { profile: Profile; size: number }) {
  if (isGuest(profile)) {
    return <MaterialCommunityIcons name="account" size={size} color="#fff" />;
  }
  // Only a face this set actually has. Profiles made against the earlier icon
  // list carry names that are not in this font, and those render as a blank
  // circle — the initial is the honest fallback.
  if (profile.icon && AVATARS.includes(profile.icon)) {
    return (
      <MaterialCommunityIcons name={profile.icon as any} size={size} color="#fff" />
    );
  }
  return (
    <Text style={{ ...type.hero, fontFamily: font.semibold, color: '#fff' }}>
      {profile.name.trim().charAt(0).toUpperCase() || '?'}
    </Text>
  );
}

/**
 * Who's watching.
 *
 * Shown every time the app opens. A television is the one screen a household
 * actually shares, and the cost of asking is one press — far less than the cost
 * of someone's resume points and watchlist quietly landing on another person's
 * shelf because the box assumed.
 */
export function ProfilesScreen({
  profiles,
  onPick,
  onAdd,
  onEdit,
  onRemove,
  onCancel,
}: {
  profiles: Profile[];
  onPick: (profile: Profile) => void;
  onAdd: (name: string, icon: string, colour: string) => void;
  onEdit: (id: string, changes: { name?: string; icon?: string; colour?: string }) => void;
  onRemove: (profile: Profile) => void;
  /** Back out without switching. Absent on first run, where there is nothing
   *  to go back to. */
  onCancel?: () => void;
}) {
  const colors = useColors();
  const [adding, setAdding] = useState(!profiles.length);
  /** The profile being edited, when Manage is on. */
  const [editing, setEditing] = useState<Profile | null>(null);
  const [managing, setManaging] = useState(false);
  const [name, setName] = useState('');
  const [icon, setIcon] = useState(AVATARS[0]);
  const [colour, setColour] = useState(COLOURS[0]);

  /** The locked profile waiting on its four digits. */
  const [asking, setAsking] = useState<Profile | null>(null);
  const [pin, setPin] = useState('');
  const [wrong, setWrong] = useState(false);

  const styles = useThemedStyles((c) => ({
    screen: {
      flex: 1,
      backgroundColor: c.bg,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      padding: SAFE.x,
    },
    heading: { ...type.hero, color: c.text, marginBottom: space.xs },
    note: { ...type.meta, color: c.faint, marginBottom: space.xxl, textAlign: 'center' as const },
    row: {
      flexDirection: 'row' as const,
      flexWrap: 'wrap' as const,
      justifyContent: 'center' as const,
      gap: space.xl,
      maxWidth: TILE * 6,
    },
    tile: { alignItems: 'center' as const, gap: space.sm, width: TILE },
    face: {
      width: TILE,
      height: TILE,
      // Round, so a profile reads as a person rather than a tile of content.
      borderRadius: TILE / 2,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
    },
    name: { ...type.label, color: c.muted },
    nameOn: { color: c.text },
    add: {
      width: TILE,
      height: TILE,
      borderRadius: TILE / 2,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      backgroundColor: c.surface,
      borderWidth: 2,
      borderColor: c.line,
    },
    form: { width: 560, gap: space.lg, alignItems: 'center' as const },
    input: {
      ...type.title,
      width: '100%' as const,
      color: c.text,
      backgroundColor: c.surface,
      borderRadius: radius.md,
      paddingHorizontal: space.lg,
      paddingVertical: space.md,
    },
    label: { ...type.meta, color: c.faint, alignSelf: 'flex-start' as const },
    picks: { flexDirection: 'row' as const, flexWrap: 'wrap' as const, gap: space.md },
    pick: {
      width: 60,
      height: 60,
      borderRadius: 30,
      alignItems: 'center' as const,
      justifyContent: 'center' as const,
      backgroundColor: c.surface,
    },
    pickOn: { borderWidth: 2, borderColor: c.accent },
    swatch: { width: 40, height: 40, borderRadius: radius.pill },
    formRow: { flexDirection: 'row' as const, gap: space.md },
    tools: { flexDirection: 'row' as const, gap: space.md, marginTop: space.xxl },
  }));

  /** Shared by the add and edit forms — the same fields either way. */
  const chooser = (
    <>
      <Text style={styles.label}>Icon</Text>
      <View style={styles.picks}>
        {AVATARS.map((a) => (
          <Focusable
            key={a}
            onPress={() => setIcon(a)}
            scale={1.08}
            style={[styles.pick, a === icon && styles.pickOn]}
          >
            {() => (
              <MaterialCommunityIcons
                name={a as any}
                size={30}
                color={a === icon ? colors.accent : colors.muted}
              />
            )}
          </Focusable>
        ))}
      </View>

      <Text style={styles.label}>Colour</Text>
      <View style={styles.picks}>
        {COLOURS.map((c) => (
          <Focusable
            key={c}
            onPress={() => setColour(c)}
            scale={1.08}
            style={[styles.pick, c === colour && styles.pickOn]}
          >
            {() => <View style={[styles.swatch, { backgroundColor: c }]} />}
          </Focusable>
        ))}
      </View>
    </>
  );

  if (asking) {
    return (
      <View style={styles.screen}>
        <Text style={styles.heading}>{asking.name}</Text>
        <Text style={styles.note}>
          {wrong ? 'That is not the PIN. Try again.' : 'Enter the four-digit PIN.'}
        </Text>
        <View style={styles.form}>
          <TextInput
            style={[styles.input, { textAlign: 'center', letterSpacing: 12 }]}
            value={pin}
            onChangeText={(v) => {
              const digits = v.replace(/\D/g, '').slice(0, 4);
              setPin(digits);
              setWrong(false);
              // Opens on the fourth digit — nobody wants to reach for OK after
              // typing a PIN they have typed a hundred times.
              if (digits.length === 4) {
                if (digits === asking.pin) {
                  onPick(asking);
                  setAsking(null);
                } else {
                  setWrong(true);
                  setPin('');
                }
              }
            }}
            placeholder="••••"
            placeholderTextColor={colors.faint}
            keyboardType="number-pad"
            secureTextEntry
            autoFocus
            maxLength={4}
          />
          <TvButton label="Back" onPress={() => setAsking(null)} />
        </View>
      </View>
    );
  }

  if (adding || editing) {
    const isEdit = !!editing;
    return (
      <ScrollView contentContainerStyle={styles.screen}>
        <Text style={styles.heading}>{isEdit ? 'Edit profile' : 'Add a profile'}</Text>
        <Text style={styles.note}>
          {isEdit
            ? 'Change the name, the face, or the colour.'
            : profiles.length
              ? 'Its own library, watchlist and resume points.'
              : 'Start with your name — you can add more later.'}
        </Text>
        <View style={styles.form}>
          {/* autoFocus and no `first` on the buttons below: the remote should
              land in the box with the keyboard already up, because typing the
              name is the only thing anyone came here to do. A button marked
              `first` took that focus instead. */}
          <TextInput
            style={styles.input}
            value={name}
            onChangeText={setName}
            placeholder="Name"
            placeholderTextColor={colors.faint}
            autoFocus
            maxLength={20}
            onSubmitEditing={() => {
              if (isEdit) onEdit(editing!.id, { name, icon, colour });
              else onAdd(name, icon, colour);
              setName('');
              setAdding(false);
              setEditing(null);
            }}
          />

          {chooser}

          <View style={styles.formRow}>
            <TvButton
              icon="check"
              variant="solid"
              label="Save"
              onPress={() => {
                if (isEdit) onEdit(editing!.id, { name, icon, colour });
                else onAdd(name, icon, colour);
                setName('');
                setAdding(false);
                setEditing(null);
              }}
            />
            {/* No way out of the very first one — the app needs someone. */}
            {(!!profiles.length || isEdit) && (
              <TvButton
                label="Cancel"
                onPress={() => {
                  setAdding(false);
                  setEditing(null);
                  setName('');
                }}
              />
            )}
            {isEdit && (
              <TvButton
                icon="trash-2"
                variant="danger"
                label="Delete"
                onPress={() => {
                  onRemove(editing!);
                  setEditing(null);
                }}
              />
            )}
          </View>
        </View>
      </ScrollView>
    );
  }

  const open = (p: Profile) => {
    if (managing) {
      setEditing(p);
      setName(p.name);
      setIcon(p.icon ?? AVATARS[0]);
      setColour(p.colour);
      setManaging(false);
      return;
    }
    if (p.pin) {
      setAsking(p);
      setPin('');
      setWrong(false);
      return;
    }
    onPick(p);
  };

  return (
    <View style={styles.screen}>
      <Text style={styles.heading}>Who's watching?</Text>
      <Text style={styles.note}>
        {managing ? 'Choose a profile to edit.' : 'Each one keeps its own library.'}
      </Text>

      <View style={styles.row}>
        {profiles.map((p, i) => (
          <Focusable
            key={p.id}
            first={i === 0}
            onPress={() => open(p)}
            style={styles.tile}
            scale={1.06}
          >
            {(focused) => (
              <>
                <View style={[styles.face, { backgroundColor: p.colour }]}>
                  <Face profile={p} size={56} />
                  {(managing || !!p.pin) && (
                    <View
                      style={{
                        position: 'absolute',
                        right: 8,
                        bottom: 8,
                        backgroundColor: managing ? colors.accent : '#00000099',
                        borderRadius: radius.pill,
                        padding: 6,
                      }}
                    >
                      <Icon name={managing ? 'edit-2' : 'lock'} size={16} color="#fff" />
                    </View>
                  )}
                </View>
                <Text style={[styles.name, focused && styles.nameOn]} numberOfLines={1}>
                  {p.name}
                </Text>
              </>
            )}
          </Focusable>
        ))}

        {/* The guest sits with the others rather than behind a menu — it is
            for the person handed the remote, who will not go looking. */}
        {!managing && (
          <Focusable onPress={() => onPick(GUEST)} style={styles.tile} scale={1.06}>
            {(focused) => (
              <>
                <View style={[styles.face, { backgroundColor: GUEST.colour }]}>
                  <Face profile={GUEST} size={56} />
                </View>
                <Text style={[styles.name, focused && styles.nameOn]}>Guest</Text>
              </>
            )}
          </Focusable>
        )}

        {!managing && profiles.length < 6 && (
          <Focusable onPress={() => setAdding(true)} style={styles.tile} scale={1.06}>
            {() => (
              <>
                <View style={styles.add}>
                  <Icon name="plus" size={40} color={colors.faint} />
                </View>
                <Text style={styles.name}>Add profile</Text>
              </>
            )}
          </Focusable>
        )}
      </View>

      <View style={styles.tools}>
        <TvButton
          icon={managing ? 'check' : 'edit-2'}
          label={managing ? 'Done' : 'Manage'}
          onPress={() => setManaging((m) => !m)}
        />
        {!!onCancel && !managing && <TvButton label="Back" onPress={onCancel} />}
      </View>
    </View>
  );
}
