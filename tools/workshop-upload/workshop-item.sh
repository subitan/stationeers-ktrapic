# Sourced by the upload scripts in this directory.

# workshop_visibility UPLOADER ITEM: prints the Workshop item's visibility (private, friends, public
# or unlisted), read with the uploader's --check, whose output goes to stderr for the record.
# Fails if the item can't be read. The visibility is taken from between the fixed fields --check
# prints around it ("owner <id>, updated <time>, visibility <v>, tags "), not from anywhere in the
# output, which also shows the item's title and tags and the Steam account's name.
workshop_visibility() {
  local check re='", owner [0-9]+, updated [^,]*, visibility ([a-z]+), tags '
  check="$("$1" --item "$2" --check)" || return 1
  echo "$check" >&2
  [[ "$check" =~ $re ]] || return 1
  echo "${BASH_REMATCH[1]}"
}
