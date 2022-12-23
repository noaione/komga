import {groupBy, mapValues} from 'lodash'
import {AuthorDto, BookDto} from '@/types/komga-books'

type AuthorsByRole = {[role: string]: string[]}

// return an object where keys are roles, and values are string[]
export function groupAuthorsByRole (authors: AuthorDto[]): AuthorsByRole {
  return mapValues(groupBy(authors, 'role'),
    authors => authors.map((author: AuthorDto) => author.name))
}

// we got multiple books with many authors,
// we want to prebuiild the authors array by role
// make sure that we empty out the roles that has
// different authors
export function buildManyAuthorsByRole(books: BookDto[]): AuthorsByRole {
  const authorsByRole = groupAuthorsByRole(books[0].metadata.authors)
  const ignoreRoles = [] as string[]

  for (const book of books) {
    const bookAuthorsByRole = groupAuthorsByRole(book.metadata.authors)

    for (const role in bookAuthorsByRole) {
      if (ignoreRoles.includes(role)) {
        continue
      }
      const authors = authorsByRole[role] || []
      const currentAuthors = bookAuthorsByRole[role] || []
      if (authors.length === 0) {
        authorsByRole[role] = currentAuthors
        continue
      }
      // check if the authors are different
      // sort the arrays to make sure we compare the same authors
      if (currentAuthors && authors.sort().join() !== currentAuthors.sort().join()) {
        // remove the role from the authorsByRole
        authorsByRole[role] = []
        ignoreRoles.push(role)
      }
    }
  }

  return authorsByRole
}