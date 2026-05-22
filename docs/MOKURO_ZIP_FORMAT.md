# Mokuro Manga Zip Format

Hoshi imports one Mokuro manga volume from a `.zip` or `.cbz` archive. The archive should
contain the output from the `mokuro` tool: one `.mokuro` JSON file and the page images named
by that file.

Recommended layout:

```text
My Manga Vol 01.mokuro
My Manga Vol 01/
  0001.jpg
  0002.jpg
  0003.jpg
```

Nested folders are also fine, as long as each `pages[*].img_path` in the `.mokuro` file can
be resolved to an image in the archive:

```text
My Manga Vol 01/
  My Manga Vol 01.mokuro
  images/
    0001.jpg
    0002.jpg
```

Requirements:

- Use one volume per archive. If several `.mokuro` files are present, Hoshi imports only one.
- The `.mokuro` file must have a non-empty `pages` array.
- Every page must have an `img_path` that points to an included image.
- Known-good image extensions are `.jpg`, `.jpeg`, `.png`, `.webp`, `.gif`, and `.bmp`.
- Do not use an HTML-only Mokuro export; Hoshi needs the `.mokuro` JSON.
- Avoid absolute paths or `..` path traversal entries.

The book title comes from the `.mokuro` `volume` field, then `title`, then the `.mokuro`
file name. During import, Hoshi rewrites the book internally to `mokuro.json` plus an
`images/` folder, so the zip does not need to match that internal layout.
