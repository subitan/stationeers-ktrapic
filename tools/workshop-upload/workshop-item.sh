# Sourced by the upload scripts in this directory.

# workshop_visibility UPLOADER ITEM: prints the Workshop item's visibility (private, friends, public
# or unlisted), read with the uploader's --check, whose output goes to stderr for the record.
# Fails if the item can't be read. The visibility is taken from between the fixed fields --check
# prints around it ("owner <id>, updated <time>, visibility <v>, tags "). The output also shows the
# Steam account's name and the item's title and tags, which the owner can set to anything, that
# sequence included, so the parse fails if the sequence appears more than once: a copy before or
# after the real fields could otherwise be taken for them.
workshop_visibility() {
  local check fields='", owner [0-9]+, updated [^,]*, visibility ([a-z]+), tags '
  check="$("$1" --item "$2" --check)" || return 1
  echo "$check" >&2
  # The greedy prefix makes this the last match, so any other one is in the text before it.
  [[ "$check" =~ ^(.*)$fields ]] || return 1
  local visibility="${BASH_REMATCH[2]}"
  if [[ "${BASH_REMATCH[1]}" =~ $fields ]]; then
    echo "Workshop item $2's --check output holds its fields more than once, so its visibility is ambiguous" >&2
    return 1
  fi
  echo "$visibility"
}
